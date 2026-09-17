package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.infrastructure.ai.llm.LlmResponseParser;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TradingParseAppService — 一句话交易解析应用服务（RFC 20260815 通道 A）。
 * <p>
 * 把用户自然语言（「买了 1000 股京东方 @5.2」）结构化为 {@link ParseResult}（symbol/name/direction/price/volume）。
 * LLM 结构化优先，失败降级正则兜底；仍无法解析 → matched=false，前端转精确表单（正确性由确认步兜底）。
 * <p>
 * 本服务只解析不落库——写入仍走 {@code POST /trading/trades}（同一确认链路，正确性在确认步拦截）。
 */
@Service
public class TradingParseAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingParseAppService.class);

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public TradingParseAppService(AiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    /** 解析结果：matched=false 时其余字段可为 null（前端转精确表单）。 */
    public record ParseResult(
            boolean matched,
            String symbol,
            String name,
            String direction, // "BUY" / "SELL"
            BigDecimal price,
            Integer volume,
            java.time.LocalDate tradeDate, // 2026-08-27：截图表格「日期」列提取；无 → null（归集当天）
            BigDecimal stopLossPrice,
            String buyPoint,
            BigDecimal targetPrice,
            String reason
    ) {
        /** 未匹配结果（matched=false，其余字段全 null）。 */
        public static ParseResult unmatched() {
            return new ParseResult(false, null, null, null, null, null, null, null, null, null, null);
        }
    }

    private static final Pattern TRADE_PATTERN = Pattern.compile(
            "(买(?:入|了|进)?|卖(?:出|了|掉)?)"                         // 1 动词
                    + "\\s*([\\u4e00-\\u9fa5A-Za-z]{2,12}|\\d{6})?"   // 2 名称/代码（可选，位置1）
                    + "\\s*(\\d+)\\s*(股|手|份)?"                  // 3 数量 + 4 单位（手×100）
                    + "\\s*([\\u4e00-\\u9fa5A-Za-z]{2,12}|\\d{6})?"   // 5 名称/代码（可选，位置2）
                    + "\\s*[@＠]?\\s*(\\d+(?:\\.\\d+)?)",        // 6 价格
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("\\d{6}");
    /**
     * 表格批量解析（2026-08-26，截图归集 P1 修复）：券商「当日委托/历史成交」截图被 VLM
     * 识别为表格文字（如「云南锗业 002428 93.480 卖出 100 已成 14:56:09」）——一句话解析器
     * 按单笔句式拆不出表格。本模式逐行提取「名称 代码 价格 买卖 数量 [尾列]」六段，
     * 每行一笔；尾列（状态/金额）判定：中文状态非「已成」类（已报/已确认/已撤 = 未成交或
     * 非交易）整行跳过；数字 = 成交金额（2026-08-27 当日成交单「名称 代码 价格 买卖 数量
     * 金额 日期」无状态列）→ 有金额即已成；无尾列 → 默认归集（列齐全即成交行）。
     */
    private static final Pattern TABLE_TRADE_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z]{2,12})\\h+(?:(\\d{6})\\h+)?([\\d.]+)\\h+(买入|卖出)\\h+(\\d+)(?:\\h+([^\\s]+))?");

    // ── 竖排表格（2026-09-17，P0-交易53）──────────────────────────────────────
    // 生产实据：GLM 把券商表格的**一行拆成 7 行**（每个单元格独占一行）——
    //     亨通光电 / 600487 / 68.270 / 买入 / 100 / 6827.000 / 13:08:59
    // 而上面 TABLE_TRADE_PATTERN 要求「名称 代码 价格 买卖 数量」**同处一行**（\h+ 分隔）
    // → 0 匹配 → 降级单笔解析（Schema 只能装一笔）→ 一张 3 笔成交的截图只落 1 笔。
    // 这里按「行类型」还原竖排表格：以方向行为锚点，向上取价格/代码/名称，向下取数量/金额/时间。
    /** 行类型：股票名称（中文/字母开头，2-12 字，可含数字如「TCL科技」）。 */
    private static final Pattern V_NAME_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9]{1,11}$");
    /** 行类型：6 位股票代码。 */
    private static final Pattern V_CODE_PATTERN = Pattern.compile("^\\d{6}$");
    /** 行类型：数字（价格 / 成交额）。 */
    private static final Pattern V_NUM_PATTERN = Pattern.compile("^\\d+(?:\\.\\d+)?$");
    /** 行类型：纯整数（成交量）。 */
    private static final Pattern V_INT_PATTERN = Pattern.compile("^\\d+$");
    /** 行类型：买卖方向（锚点）。 */
    private static final Pattern V_DIRECTION_PATTERN = Pattern.compile("^(买入|卖出)$");
    /** 行类型：成交时间 HH:mm / HH:mm:ss。 */
    private static final Pattern V_TIME_PATTERN = Pattern.compile("^\\d{1,2}:\\d{2}(?::\\d{2})?$");
    /** 表头行——含这些词的行不是数据（否则「名称/代码」会被当成股票名）。 */
    private static final Pattern V_HEADER_PATTERN =
            Pattern.compile("名称|代码|成交价|成交量|成交额|买卖|成交时间|成交日期|成交均价|发生金额|成交数量");
    /** 状态命中「已成/部成/全部成交」才归集（含"成"字）；「已报/已确认/已撤」为未成交或非交易。 */
    private static final Pattern FILLED_STATUS_PATTERN = Pattern.compile("成");
    /** 尾列是数字 = 成交金额（无状态列成交单）；是中文 = 状态词（走「成」字过滤）。 */
    private static final Pattern TAIL_IS_AMOUNT_PATTERN = Pattern.compile("\\d");
    /** 新股申购等非二级市场交易：名称含「申购/认购/配号」跳过（天博申购 732448 / 天博配号 736448 等）。 */
    private static final Pattern NON_TRADE_NAME_PATTERN = Pattern.compile("申购|认购|配号");
    /** 2026-08-27：表格行内成交日期——完整 yyyy-MM-dd / yyyy/MM/dd（优先）。 */
    private static final Pattern TRADE_DATE_FULL_PATTERN = Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}");
    /** 2026-08-27：短日期 MM-dd / M-d（无年份；跨年回退去年——1 月看去年 12 月截图）。 */
    private static final Pattern TRADE_DATE_SHORT_PATTERN = Pattern.compile("(?<![0-9])\\d{1,2}[-/]\\d{1,2}(?![0-9])");
    /** 正则兜底：止损位（RFC 20260816 §4.3：「止损 Z」→ stopLossPrice）。 */
    private static final Pattern STOP_LOSS_PATTERN = Pattern.compile("止损\\s*([\\d.]+)");
    /** 正则兜底：买点（RFC 20260816 §4.3：「，B1/B2/B3/SB1」→ buyPoint）。 */
    private static final Pattern BUY_POINT_PATTERN = Pattern.compile("[，,]\\s*(B1|B2|B3|SB1)(?![A-Za-z0-9])");

    /**
     * 解析一句话交易。
     */
    public ParseResult parse(String userId, String text) {
        if (text == null || text.isBlank()) {
            return ParseResult.unmatched();
        }
        String trimmed = text.trim();

        // 1) LLM 结构化优先
        try {
            ParseResult llm = parseWithLlm(userId, trimmed);
            if (llm != null && llm.matched()) {
                return llm;
            }
        } catch (Exception e) {
            log.warn("一句话交易 LLM 解析失败，降级正则 | {}", e.getMessage());
        }

        // 2) 正则兜底
        return parseWithRegex(trimmed);
    }

    /**
     * 宽松解析（RFC 20260817 交易日志归集用）：只要识别出「买卖方向 + 股票（代码/名称）」即 matched，
     * 数量/价格可空（complete=false）——「清仓了云南锗业」这种无数字表述也归集为待补充候选。
     * 严格模式（{@link #parse}）用于前端交易表单回显，必须价格+数量齐全。
     */
    public ParseResult parseLoose(String userId, String text) {
        if (text == null || text.isBlank()) {
            return ParseResult.unmatched();
        }
        String trimmed = text.trim();
        try {
            var ctx = com.adaiadai.core.kernel.context.engine.ContextPackage.simple(
                    "trading", null, "交易识别", "识别交易动作：" + trimmed,
                    java.util.List.of("trading"), "识别交易动作：" + trimmed);
            String raw = aiClient.generate(ctx, LOOSE_SYSTEM_PROMPT);
            if (raw != null && !raw.isBlank()) {
                return parseLooseResult(raw);
            }
        } catch (Exception e) {
            log.warn("宽松交易解析 LLM 失败，降级正则 | {}", e.getMessage());
        }
        // 正则兜底：匹配「买/卖/清仓 + 名称」即使无数量价格
        ParseResult strict = parseWithRegex(trimmed);
        if (strict.matched()) return strict;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(买(?:入|了|进)?|卖(?:出|了|掉)?|清仓)\\s*([\\u4e00-\\u9fa5A-Za-z]{2,12}|\\d{6})").matcher(trimmed);
        if (m.find()) {
            String verb = m.group(1);
            String stock = m.group(2);
            boolean sell = verb.contains("卖") || verb.contains("清仓");
            String symbol = stock.matches("\\d{6}") ? stock : null;
            return new ParseResult(true, symbol, symbol == null ? stock : null,
                    sell ? "SELL" : "BUY", null, null, null, null, null, null, null);
        }
        return ParseResult.unmatched();
    }

    private static final String LOOSE_SYSTEM_PROMPT = """
            你是交易动作识别器。从用户文本提取交易动作，只输出 JSON：
            {"matched": true/false, "symbol": "6位代码或null", "name": "股票名称或null",
             "direction": "BUY"或"SELL"或null, "price": 数字或null, "volume": 整数或null}
            规则：
            - 有明确的买/卖/清仓动词 + 股票名（或代码）→ matched=true，direction 必填
            - 数量/价格没有 → null（不因此判 unmatched）
            - 纯闲聊（无买卖动词或股票）→ matched=false
            """.strip();

    /**
     * 表格批量解析（2026-08-26，截图归集缺口修复）：券商「当日委托」截图被 VLM 识别成
     * 表格文字（一行多笔，如「云南锗业 002428 93.480 卖出 100 已成 14:56:09 …」）。
     * 一句话解析器（{@link #parseLoose}）按单笔句式拆不出表格 → 0 候选；
     * 本方法按表格行模式逐笔提取，返回多笔 {@link ParseResult}（每笔完整：symbol+direction+price+volume）。
     * <p>
     * 过滤规则：
     * <ul>
     *   <li>状态非「已成」类（已报/已确认/已撤 = 未成交或非交易）→ 整行跳过</li>
     *   <li>名称含「申购/认购」（新股申购如 732448）→ 非二级市场交易，跳过</li>
     *   <li>79/80/81/82 开头占位代码（通达信非交易段）→ 跳过（与历史成交导入同口径）</li>
     * </ul>
     *
     * @return 解析出的完整交易笔列表（可能为空 = 非交易截图/表格）
     */
    public List<ParseResult> parseLooseBatch(String userId, String text) {
        return parseLooseBatchDetailed(userId, text).trades();
    }

    /**
     * 表格批量解析（带「被丢掉的行」）——2026-09-14 P2-交易44 同型风险封堵。
     * <p>
     * 原实现五处 {@code log.debug} 静默丢行，响应里只有<b>图片级</b> errors + 候选列表 ——
     * 用户看到「识别出 2 笔」，不知道同一张截图里第 3 笔被丢了（截图入账是核心工作流）。
     * 现在每一处丢弃都带<b>行原文 + 原因</b>上报（「有意跳过」与「没认出来」原因分开写清），
     * 由调用方透出到响应。
     *
     * @return trades = 识别出的交易笔；dropped = 被丢弃的表格行（原文 + 原因）
     */
    public LooseBatchParse parseLooseBatchDetailed(String userId, String text) {
        if (text == null || text.isBlank()) {
            return new LooseBatchParse(List.of(), List.of());
        }
        List<ParseResult> results = new java.util.ArrayList<>();
        List<TradingImportParser.UnparsedLine> dropped = new java.util.ArrayList<>();
        Matcher m = TABLE_TRADE_PATTERN.matcher(text);
        int seq = 0;
        // P1-交易55（2026-09-17）：记录横排正则**覆盖到的行首偏移**——扫描结束后用它找出
        // 「像成交却一行都没匹配上」的行（过去这些行彻底静默：既不进候选、也不进 dropped）。
        java.util.Set<Integer> coveredStarts = new java.util.HashSet<>();
        while (m.find()) {
            // 2026-08-27：成交日期提取——表格行内「日期」列（历史成交截图常带 yyyy-MM-dd）。
            // 从匹配行（行首到行尾）的两侧文本里找日期，不依赖列序：行首（「2026-08-26 名称 代码 …」）
            // 或行尾（「… 金额 2026-08-26 14:56:09」）均可；当日成交单无日期列 → null（归集当天）。
            int lineStart = Math.max(0, text.lastIndexOf('\n', m.start()) + 1);
            int lineEnd = text.indexOf('\n', m.end());
            if (lineEnd < 0) lineEnd = text.length();
            coveredStarts.add(lineStart);
            String rowBefore = text.substring(lineStart, m.start());
            String rowAfter = text.substring(m.end(), lineEnd);
            java.time.LocalDate tradeDate = extractTradeDate(rowBefore + " " + rowAfter);
            seq++;
            String rawRow = text.substring(lineStart, lineEnd).trim();

            String name = m.group(1).trim();
            // 2026-08-27：代码列可选（VLM OCR 不稳定，同图两次可能漏代码列）——无代码行
            // 保留 name，由归集器按名称查代码补 symbol（NameToSymbolResolver）。
            String symbol = m.group(2) != null ? m.group(2).trim() : null;
            String tail = m.group(6) != null ? m.group(6).trim() : "";
            // 新股申购/认购/配号：非二级市场交易（天博申购 732448 / 天博配号 736448 等）。
            // P2-交易44（2026-09-14）：本判定与占位代码判定**提到状态判定之前**——「天博申购…已确认」
            // 若先按状态拦下，用户看到的原因是「已确认不是已成」（像在说他单子没成交），
            // 而真正的原因是「申购本来就不会记」（语义更强、也更准）。
            if (NON_TRADE_NAME_PATTERN.matcher(name).find()) {
                log.debug("表格行跳过（新股申购/认购/配号）| {} {} 尾列={}", name, symbol, tail);
                dropped.add(new TradingImportParser.UnparsedLine(seq, rawRow,
                        "「" + name + "」是申购/认购/配号，不是二级市场买卖（没有记）"));
                continue;
            }
            // 通达信占位代码（79/80/81/82）——与历史成交导入同口径
            if (symbol != null && com.adaiadai.core.application.TradingImportParser.isNonTradableCode(symbol)) {
                log.debug("表格行跳过（占位代码）| {} {} 尾列={}", name, symbol, tail);
                dropped.add(new TradingImportParser.UnparsedLine(seq, rawRow,
                        "「" + symbol + "」是券商占位代码，不是真实股票（没有记）"));
                continue;
            }
            // 尾列判定（2026-08-27 兼容无状态列成交单）：
            // - 空 → 无状态列（名称 代码 价格 买卖 数量 金额 日期）→ 默认归集
            // - 数字 → 成交金额（30198.00）→ 有金额即已成 → 归集
            // - 中文状态词 → 含"成"（已成/部成）归集；已报/已确认/已撤 → 跳过
            if (!tail.isEmpty() && !TAIL_IS_AMOUNT_PATTERN.matcher(tail).find()) {
                if (!FILLED_STATUS_PATTERN.matcher(tail).find()) {
                    log.debug("表格行跳过（未成交/非交易状态）| {} {} {} 状态={}", name, symbol, tail);
                    dropped.add(new TradingImportParser.UnparsedLine(seq, rawRow,
                            "状态「" + tail + "」不是已成/部成（未成交的单子没有记）"));
                    continue;
                }
            }
            BigDecimal price;
            try {
                price = new BigDecimal(m.group(3).trim());
            } catch (NumberFormatException e) {
                log.debug("表格行价格解析失败，跳过 | {} {}", name, symbol);
                dropped.add(new TradingImportParser.UnparsedLine(seq, rawRow,
                        "价格「" + m.group(3).trim() + "」没认出来（这一笔没有记）"));
                continue;
            }
            int volume;
            try {
                volume = Integer.parseInt(m.group(5).trim());
            } catch (NumberFormatException e) {
                log.debug("表格行数量解析失败，跳过 | {} {}", name, symbol);
                dropped.add(new TradingImportParser.UnparsedLine(seq, rawRow,
                        "数量「" + m.group(5).trim() + "」没认出来（这一笔没有记）"));
                continue;
            }
            if (price.compareTo(BigDecimal.ZERO) <= 0 || volume <= 0) {
                dropped.add(new TradingImportParser.UnparsedLine(seq, rawRow,
                        "价格/数量为 " + price.toPlainString() + " / " + volume + "，不是有效成交（这一笔没有记）"));
                continue;
            }
            String direction = "买入".equals(m.group(4)) ? "BUY" : "SELL";
            // P1-交易56（2026-09-17 B2 批）：横排同样做「价格 × 数量 ≈ 成交额」交叉校验——
            // 列错位 / OCR 漏列时，成交额会被正则当成「数量」捕获（生产实据：成交额 6827 元
            // 被写成 6827 股），而 complete = symbol && price && volume 会判它合法并污染持仓。
            // 尾列是数字、且不是时间（HH:mm / HH:mm:ss）时才当成交额用；反推不出正整数就保持原值。
            // 注：单笔 LLM 路径的 Schema 不含成交额字段，无法做同一校验（已如实登记，待跨层改动）。
            if (!tail.isEmpty() && TAIL_IS_AMOUNT_PATTERN.matcher(tail).find()
                    && !V_TIME_PATTERN.matcher(tail).matches()) {
                BigDecimal amount = parseAmountOrNull(tail);
                int fixed = reconcileVolume(price, volume, amount);
                if (fixed != volume) {
                    log.info("横排表格：数量与成交额对不上，按「成交额÷价格」修正 | {} {} {} 股 → {} 股（价 {} 额 {}）",
                            symbol != null ? symbol : name, direction, volume, fixed, price, amount);
                    volume = fixed;
                }
            }
            results.add(new ParseResult(true, symbol, name, direction, price, volume,
                    tradeDate, null, null, null, null));
        }
        // 2026-09-17（P0-交易53）：横排正则 0 命中 → 尝试竖排表格（VLM 把表格的一行拆成多行）。
        if (results.isEmpty()) {
            parseVerticalTable(text, results, dropped);
        }
        // P1-交易55（2026-09-17）：横排/竖排都没还原出来 → 把「有买卖字样却没匹配上」的行如实上报。
        // 竖排已给出更精确的丢弃明细时不重复报（否则同一行会在 dropped 里出现两次）。
        if (results.isEmpty() && dropped.isEmpty()) {
            reportUnmatchedRows(text, coveredStarts, dropped, seq);
        }
        if (!results.isEmpty()) {
            log.info("表格批量解析 | 命中 {} 笔 | 丢弃 {} 行 | 文本前 80 字: {}", results.size(), dropped.size(),
                    text.length() > 80 ? text.substring(0, 80) : text);
        } else if (!dropped.isEmpty()) {
            log.info("表格批量解析 | 命中 0 笔 | 丢弃 {} 行 | 文本前 80 字: {}", dropped.size(),
                    text.length() > 80 ? text.substring(0, 80) : text);
        } else {
            // P1-交易55：横排/竖排都没命中也要留痕，便于从生产日志区分「图糊了」与「版式不支持」。
            log.info("表格批量解析 | 命中 0 笔 | 无丢弃行 | 疑似非交易截图或未支持版式 | 文本前 80 字: {}",
                    text.length() > 80 ? text.substring(0, 80) : text);
        }
        return new LooseBatchParse(results, dropped);
    }

    /** 从文本里抠出金额（容忍千分位逗号与「元」等尾缀）；抠不出数字返回 null，不抛。 */
    private static BigDecimal parseAmountOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = Pattern.compile("([\\d,]+(?:\\.\\d+)?)").matcher(text.trim());
        if (!m.find()) return null;
        try {
            return new BigDecimal(m.group(1).replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 「价格 × 数量 = 成交额」恒等式的反推（P1-交易56）。
     *
     * <p>列错位 / OCR 漏列时，成交额会被当成股数落库（生产实据：成交额 6827 元写成
     * {@code volume=6827}），而 {@code complete = symbol && price && volume} 会判它合法、
     * 让它污染持仓数量与成本。判据与竖排一致：{@code 成交额 ÷ 价格} 是**正整数**、且与原数量
     * 超出容差（原值 1% 与 0.5 取大）时，以反推值作为数量（金额列比数量列长、更不易被列错位带走）。
     *
     * <p>反推不出正整数时**原样返回**——没把握就不改（宁可少修，不可把真数量改错）。
     *
     * @return 修正后的数量；无法判定时返回传入的 {@code volume}
     */
    private static int reconcileVolume(BigDecimal price, int volume, BigDecimal amount) {
        if (price == null || price.signum() <= 0 || amount == null || amount.signum() <= 0) {
            return volume;
        }
        BigDecimal expect = amount.divide(price, 4, java.math.RoundingMode.HALF_UP);
        if (expect.stripTrailingZeros().scale() > 0) {
            return volume;
        }
        int expected;
        try {
            expected = expect.intValueExact();
        } catch (ArithmeticException e) {
            return volume;
        }
        if (expected <= 0) {
            return volume;
        }
        BigDecimal actual = BigDecimal.valueOf(volume);
        BigDecimal tolerance = actual.multiply(new BigDecimal("0.01")).max(new BigDecimal("0.5"));
        return expect.subtract(actual).abs().compareTo(tolerance) > 0 ? expected : volume;
    }

    /**
     * 竖排表格还原（2026-09-17，P0-交易53）：VLM 把「一行多列」输出成「一列一行」时的解析。
     *
     * <p>以方向行（买入/卖出）为锚点：向上取「价格（必需）→ 代码（可选）→ 名称（可选）」，
     * 向下取「数量（必需）→ 成交额（可选）→ 时间（可选）」。四要素（名称或代码 + 价格 + 方向 + 数量）
     * 不全则**不产出**——没把握时不猜，宁可由上层单笔解析兜底，也不把闲聊文本误判成成交。
     *
     * <p>成交额交叉校验（P1-交易56）：`价格 × 数量 = 成交额` 是恒等式。对不上、且
     * 「成交额 ÷ 价格」是正整数时，以反推值作为数量（金额列比数量列长、更不易被列错位带走），
     * 避免把成交额当成股数落库。
     */
    private void parseVerticalTable(String text, List<ParseResult> results,
                                    List<TradingImportParser.UnparsedLine> dropped) {
        List<String> lines = new java.util.ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String s = raw.trim();
            if (s.isEmpty() || V_HEADER_PATTERN.matcher(s).find()) continue;
            lines.add(s);
        }
        if (lines.size() < 4) return; // 一笔竖排成交至少 4 行（名称/代码 + 价格 + 方向 + 数量）
        int seq = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!V_DIRECTION_PATTERN.matcher(lines.get(i)).matches()) continue;
            String direction = "买入".equals(lines.get(i)) ? "BUY" : "SELL";
            int up = i - 1;
            BigDecimal price = null;
            if (up >= 0 && V_NUM_PATTERN.matcher(lines.get(up)).matches()
                    && !V_CODE_PATTERN.matcher(lines.get(up)).matches()) {
                price = new BigDecimal(lines.get(up));
                up--;
            }
            String symbol = null;
            if (up >= 0 && V_CODE_PATTERN.matcher(lines.get(up)).matches()) {
                symbol = lines.get(up);
                up--;
            }
            String name = null;
            if (up >= 0 && V_NAME_PATTERN.matcher(lines.get(up)).matches()) {
                name = lines.get(up);
            }
            int down = i + 1;
            Integer volume = null;
            if (down < lines.size() && V_INT_PATTERN.matcher(lines.get(down)).matches()) {
                try {
                    volume = Integer.parseInt(lines.get(down));
                    down++;
                } catch (NumberFormatException ignored) {
                    volume = null;
                }
            }
            BigDecimal amount = null;
            if (down < lines.size() && V_NUM_PATTERN.matcher(lines.get(down)).matches()) {
                amount = new BigDecimal(lines.get(down));
            }
            seq++;
            if (price == null || volume == null || (symbol == null && name == null)) {
                // P1-交易55：认出了方向却凑不齐一笔 → 如实上报，不静默丢弃。
                if (price != null && (symbol != null || name != null)) {
                    int from = Math.max(0, i - 3);
                    int to = Math.min(lines.size(), i + 4);
                    dropped.add(new TradingImportParser.UnparsedLine(seq,
                            String.join(" / ", lines.subList(from, to)),
                            "像是成交但缺" + (volume == null ? "数量" : "价格") + "（这一笔没有记）"));
                }
                continue;
            }
            if (price.signum() <= 0 || volume <= 0) continue;
            if (amount != null && amount.signum() > 0) {
                int fixed = reconcileVolume(price, volume, amount);
                if (fixed != volume) {
                    log.info("竖排表格：数量与成交额对不上，按「成交额÷价格」修正 | {} {} {} 股 → {} 股（价 {} 额 {}）",
                            symbol != null ? symbol : name, direction, volume, fixed, price, amount);
                    volume = fixed;
                }
            }
            results.add(new ParseResult(true, symbol, name, direction, price, volume,
                    null, null, null, null, null));
        }
        if (!results.isEmpty()) {
            log.info("竖排表格解析 | 还原 {} 笔（VLM 一行拆多行的版式）", results.size());
        }
    }

    /**
     * P1-交易55（2026-09-17）：把「像成交、却一行都没认出来」的行如实上报。
     *
     * <p>过去这些行**彻底静默**——横排正则只遍历匹配到的行，没匹配上的既不进候选也不进
     * {@code dropped}，用户只看到「没认出来」，无法判断是图糊了、还是解析器不支持这种版式。
     * 判据刻意保守：只报「未被任何匹配覆盖 **且** 含『买』或『卖』」的行，避免把券商抬头、
     * 免责声明、按钮文字也算成丢行。
     */
    private void reportUnmatchedRows(String text, java.util.Set<Integer> coveredStarts,
                                     List<TradingImportParser.UnparsedLine> dropped, int seqStart) {
        String[] lines = text.split("\n", -1);
        int offset = 0;
        int seq = seqStart;
        for (String raw : lines) {
            if (!coveredStarts.contains(offset)) {
                String s = raw.trim();
                // 排除表头行（「成交价/买卖」这种含「买」字但不是成交数据）——否则会把表头误报成丢行
                if (!s.isEmpty() && !V_HEADER_PATTERN.matcher(s).find()
                        && (s.contains("买") || s.contains("卖"))) {
                    seq++;
                    dropped.add(new TradingImportParser.UnparsedLine(seq, s,
                            "这行有买卖字样，但代码/价格/数量/方向没凑齐（这一笔没有记）"));
                }
            }
            offset += raw.length() + 1; // 与 lineStart 的 \n 偏移口径一致
        }
    }

    /** 表格批量解析结果（2026-09-14 P2-交易44）：识别出的交易 + 被丢弃的行（原文 + 原因）。 */
    public record LooseBatchParse(List<ParseResult> trades,
                                  List<TradingImportParser.UnparsedLine> dropped) {
        public LooseBatchParse {
            if (trades == null) trades = List.of();
            if (dropped == null) dropped = List.of();
        }
    }

    /**
     * 2026-08-27：从表格行文本提取成交日期（历史成交截图「日期」列）。
     * 完整格式 yyyy-MM-dd / yyyy/MM/dd 优先；短格式 MM-dd / M/d 无年份 → 当年，
     * 晚于今天视为去年（跨年场景：1 月归集去年 12 月成交截图）。提取不到返回 null（归集当天兜底）。
     */
    private static java.time.LocalDate extractTradeDate(String row) {
        if (row == null || row.isBlank()) return null;
        Matcher full = TRADE_DATE_FULL_PATTERN.matcher(row);
        if (full.find()) {
            try {
                return java.time.LocalDate.parse(full.group().replace('/', '-'));
            } catch (Exception ignored) {
                // 落到短格式
            }
        }
        Matcher shortM = TRADE_DATE_SHORT_PATTERN.matcher(row);
        if (shortM.find()) {
            try {
                String[] p = shortM.group().split("[-/]");
                int month = Integer.parseInt(p[0]);
                int day = Integer.parseInt(p[1]);
                if (month < 1 || month > 12 || day < 1 || day > 31) return null;
                int year = java.time.LocalDate.now().getYear();
                java.time.LocalDate d = java.time.LocalDate.of(year, month, day);
                if (d.isAfter(java.time.LocalDate.now())) d = d.minusYears(1);
                return d;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private ParseResult parseLooseResult(String raw) {
        try {
            var node = objectMapper.readTree(LlmResponseParser.stripCodeFences(raw));
            if (node == null || !node.has("matched") || !node.get("matched").asBoolean(false)) {
                return ParseResult.unmatched();
            }
            String direction = node.hasNonNull("direction") ? node.get("direction").asText().trim().toUpperCase(Locale.ROOT) : null;
            if (!"BUY".equals(direction) && !"SELL".equals(direction)) {
                return ParseResult.unmatched();
            }
            String symbol = node.hasNonNull("symbol") ? node.get("symbol").asText().trim() : null;
            String name = node.hasNonNull("name") ? node.get("name").asText().trim() : null;
            if ((symbol == null || symbol.isBlank() || "null".equals(symbol))
                    && (name == null || name.isBlank() || "null".equals(name))) {
                return ParseResult.unmatched();
            }
            BigDecimal price = null;
            if (node.hasNonNull("price") && node.get("price").isNumber()) {
                price = node.get("price").decimalValue();
                if (price.compareTo(BigDecimal.ZERO) <= 0) price = null;
            }
            Integer volume = null;
            if (node.hasNonNull("volume") && node.get("volume").isInt()) {
                volume = node.get("volume").asInt();
                if (volume <= 0) volume = null;
            }
            return new ParseResult(true, symbol, name, direction, price, volume, null, null, null, null, null);
        } catch (Exception e) {
            log.warn("宽松交易解析输出不可解析 | {}", e.getMessage());
            return ParseResult.unmatched();
        }
    }

    private ParseResult parseWithLlm(String userId, String text) {
        String prompt = """
                你是 AdaiOS 的交易记录解析器。把用户一句话交易意图结构化为 JSON。
                只输出 JSON，不要任何其他文字。

                规则：
                - direction 只允许 "BUY" 或 "SELL"（买入=BUY，卖出=SELL）
                - price 是每股价格（数字）
                - volume 是数量（整数，股数）——注意单位换算：用户说「5 手」= 500 股（1手=100股），「3 份」= 3 股（份=股）；必须换算成股数
                - symbol 是 6 位代码（若有）；name 是股票名称（若有）；都没有则 null
                - stopLossPrice 是止损位（数字，若有；买入通常必填）
                - buyPoint 是买点类型（B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他，若有）
                - targetPrice 是目标价（数字，若有）
                - reason 是交易原因/预期（文本，若有）
                - 无法确定 direction 或缺少关键数字时 matched=false，其余字段 null
                - 必须包含 matched 字段

                用户输入：%s

                输出 JSON 格式：
                {"matched": true, "symbol": "000725", "name": "京东方A", "direction": "BUY", "price": 5.2, "volume": 1000, "stopLossPrice": 4.9, "buyPoint": "B1", "targetPrice": 6.0, "reason": "突破买入"}
                """.formatted(text);

        ContextPackage ctx = ContextPackage.simple(
                "trading", null, "一句话交易解析", prompt,
                List.of("trading", "parse"), prompt);
        AiTraceContext.set(userId, null, null, "trading_parse");
        String raw = aiClient.generate(ctx, "你是交易记录解析器，只输出 JSON。");
        JsonNode node;
        try {
            node = objectMapper.readTree(LlmResponseParser.stripCodeFences(raw));
        } catch (Exception e) {
            log.warn("一句话交易 LLM 输出不可解析 | {}", e.getMessage());
            return ParseResult.unmatched();
        }
        if (node == null || !node.has("matched") || !node.get("matched").asBoolean(false)) {
            return ParseResult.unmatched();
        }
        String direction = node.hasNonNull("direction") ? node.get("direction").asText().trim().toUpperCase(Locale.ROOT) : null;
        if (!"BUY".equals(direction) && !"SELL".equals(direction)) {
            return ParseResult.unmatched();
        }
        BigDecimal price = node.hasNonNull("price") ? node.get("price").decimalValue() : null;
        Integer volume = node.hasNonNull("volume") ? node.get("volume").asInt() : null;
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0 || volume == null || volume <= 0) {
            return ParseResult.unmatched();
        }
        String symbol = node.hasNonNull("symbol") && !node.get("symbol").asText().isBlank() ? node.get("symbol").asText().trim() : null;
        String name = node.hasNonNull("name") && !node.get("name").asText().isBlank() ? node.get("name").asText().trim() : null;
        BigDecimal stopLossPrice = node.hasNonNull("stopLossPrice") ? node.get("stopLossPrice").decimalValue() : null;
        String buyPoint = node.hasNonNull("buyPoint") && !node.get("buyPoint").asText().isBlank()
                ? node.get("buyPoint").asText().trim() : null;
        BigDecimal targetPrice = node.hasNonNull("targetPrice") ? node.get("targetPrice").decimalValue() : null;
        String reason = node.hasNonNull("reason") && !node.get("reason").asText().isBlank()
                ? node.get("reason").asText().trim() : null;
        return new ParseResult(true, symbol, name, direction, price, volume,
                null, stopLossPrice, buyPoint, targetPrice, reason);
    }

    private ParseResult parseWithRegex(String text) {
        Matcher m = TRADE_PATTERN.matcher(text);
        if (!m.find()) {
            return ParseResult.unmatched();
        }
        String verb = m.group(1);
        String position1 = m.group(2);
        String unit = m.group(4);  // 股/手/份（手×100）
        String position2 = m.group(5);
        String direction = verb.startsWith("买") ? "BUY" : "SELL";

        // 名称/代码取位置2优先（数量后），否则位置1
        String symbolOrName = (position2 != null && !position2.isBlank()) ? position2 : position1;
        String symbol = null;
        String name = null;
        if (symbolOrName != null && !symbolOrName.isBlank()) {
            if (SYMBOL_PATTERN.matcher(symbolOrName).matches()) {
                symbol = symbolOrName;
            } else {
                name = symbolOrName;
            }
        }

        Integer volume;
        try {
            volume = Integer.parseInt(m.group(3));
            if ("手".equals(unit)) volume = volume * 100;  // 1手=100股（A股）
        } catch (NumberFormatException e) {
            return ParseResult.unmatched();
        }
        BigDecimal price;
        try {
            price = new BigDecimal(m.group(6));
        } catch (NumberFormatException e) {
            return ParseResult.unmatched();
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0 || volume <= 0) {
            return ParseResult.unmatched();
        }

        // 正则兜底：止损/买点（RFC 20260816 §4.3）
        BigDecimal stopLossPrice = null;
        Matcher stopLossMatcher = STOP_LOSS_PATTERN.matcher(text);
        if (stopLossMatcher.find()) {
            try {
                stopLossPrice = new BigDecimal(stopLossMatcher.group(1));
            } catch (NumberFormatException e) {
                stopLossPrice = null;
            }
        }
        String buyPoint = null;
        Matcher buyPointMatcher = BUY_POINT_PATTERN.matcher(text);
        if (buyPointMatcher.find()) {
            buyPoint = buyPointMatcher.group(1);
        }

        return new ParseResult(true, symbol, name, direction, price, volume,
                null, stopLossPrice, buyPoint, null, null);
    }
}
