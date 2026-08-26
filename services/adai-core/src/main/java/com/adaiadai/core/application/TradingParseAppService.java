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
            BigDecimal stopLossPrice,
            String buyPoint,
            BigDecimal targetPrice,
            String reason
    ) {
        /** 未匹配结果（matched=false，其余字段全 null）。 */
        public static ParseResult unmatched() {
            return new ParseResult(false, null, null, null, null, null, null, null, null, null);
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
     * 按单笔句式拆不出表格。本模式逐行提取「名称 代码 价格 买卖 数量 状态」六段，
     * 每行一笔；状态非「已成」类（已报/已确认/已撤 = 未成交或非交易）整行跳过。
     */
    private static final Pattern TABLE_TRADE_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z]{2,12})\\s+(\\d{6})\\s+([\\d.]+)\\s+(买入|卖出)\\s+(\\d+)\\s*([^\\s]+)");
    /** 状态命中「已成/部成/全部成交」才归集（含"成"字）；「已报/已确认/已撤」为未成交或非交易。 */
    private static final Pattern FILLED_STATUS_PATTERN = Pattern.compile("成");
    /** 新股申购等非二级市场交易：名称含「申购/认购」跳过（天博申购 732448 等）。 */
    private static final Pattern NON_TRADE_NAME_PATTERN = Pattern.compile("申购|认购");
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
                    sell ? "SELL" : "BUY", null, null, null, null, null, null);
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
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ParseResult> results = new java.util.ArrayList<>();
        Matcher m = TABLE_TRADE_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1).trim();
            String symbol = m.group(2).trim();
            String status = m.group(6).trim();
            // 状态过滤：只归集已成/部成（含"成"字）；已报（未成交）、已确认（非交易）、已撤 → 跳过
            if (!FILLED_STATUS_PATTERN.matcher(status).find()) {
                log.debug("表格行跳过（未成交/非交易状态）| {} {} {} 状态={}", name, symbol, status);
                continue;
            }
            // 新股申购/认购：非二级市场交易（天博申购 732448 等）
            if (NON_TRADE_NAME_PATTERN.matcher(name).find()) {
                log.debug("表格行跳过（新股申购/认购）| {} {} 状态={}", name, symbol, status);
                continue;
            }
            // 通达信占位代码（79/80/81/82）——与历史成交导入同口径
            if (com.adaiadai.core.application.TradingImportParser.isNonTradableCode(symbol)) {
                log.debug("表格行跳过（占位代码）| {} {} 状态={}", name, symbol, status);
                continue;
            }
            BigDecimal price;
            try {
                price = new BigDecimal(m.group(3).trim());
            } catch (NumberFormatException e) {
                log.debug("表格行价格解析失败，跳过 | {} {}", name, symbol);
                continue;
            }
            int volume;
            try {
                volume = Integer.parseInt(m.group(5).trim());
            } catch (NumberFormatException e) {
                log.debug("表格行数量解析失败，跳过 | {} {}", name, symbol);
                continue;
            }
            if (price.compareTo(BigDecimal.ZERO) <= 0 || volume <= 0) {
                continue;
            }
            String direction = "买入".equals(m.group(4)) ? "BUY" : "SELL";
            results.add(new ParseResult(true, symbol, name, direction, price, volume,
                    null, null, null, null));
        }
        if (!results.isEmpty()) {
            log.info("表格批量解析 | 命中 {} 笔 | 文本前 80 字: {}", results.size(),
                    text.length() > 80 ? text.substring(0, 80) : text);
        }
        return results;
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
            return new ParseResult(true, symbol, name, direction, price, volume, null, null, null, null);
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
                stopLossPrice, buyPoint, targetPrice, reason);
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
                stopLossPrice, buyPoint, null, null);
    }
}
