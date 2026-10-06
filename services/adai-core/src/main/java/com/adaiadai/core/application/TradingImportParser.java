package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.WatchlistItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TradingImportParser — 通达信导出文本解析（RFC 20260816 交易数据智能）。
 * <p>
 * 表头定位列（版本差异容忍），三种格式：
 * <ul>
 *   <li>自选股：代码/名称/细分行业/一二级行业/长期形态/中期形态/短期形态/近日指标提示</li>
 *   <li>清仓股：代码/名称/介入日期/清仓日期/持仓天数/买卖次数/持仓期涨幅%</li>
 *   <li>资金股份查询：首行「余额/资产」+ 明细（证券代码/成本价）</li>
 * </ul>
 * 文件为 GBK 编码时由调用方先转码（TradingAppService.saveImportFile）。
 */
public final class TradingImportParser {

    private static final Pattern CASH_HEAD = Pattern.compile(
            "余额[:：]\\s*([\\d,.]+)\\s+可用[:：]\\s*([\\d,.]+)\\s+可取[:：]\\s*([\\d,.]+)"
                    + "\\s+参考市值[:：]\\s*([\\d,.]+)\\s+资产[:：]\\s*([\\d,.]+)\\s+盈亏[:：]\\s*([\\d,.]+)");
    private static final DateTimeFormatter TDX_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 文件名里的日期（`20260904004455_持仓股20260904.txt` → 2026-09-04；也容忍 `2026-09-04` / `2026_09_04`）——
     *  统一入口给快照两类文件定锚定日（设计 §3①「归一化」）；假日期（`20260230` 等）由
     *  {@link #parseDateFromFilename} 逐字段回验剔除。 */
    private static final Pattern FILENAME_DATE = Pattern.compile(
            "(20\\d{2})[-_/]?(0\\d|1[0-2])[-_/]?(0[1-9]|[12]\\d|3[01])");

    private TradingImportParser() {}

    /** 通达信占位代码（非可交易资产）：79/80/81/82 开头 6 位——如 799999「登记指定」/配号等券商占位，
     *  不是真实股票（2026-08-25 用户反馈：明显非股票代码被照单入库）。命中 → 导入跳过并计入 nonTrades。 */
    public static boolean isNonTradableCode(String symbol) {
        return symbol != null && symbol.matches("^(79|80|81|82)\\d{4}$");
    }

    /** 解析自选股导出 → 自选条目（表头定位列）。
     *  <p>核心列校验（2026-08-27 用户事故：清仓股文件被导入自选 → 170 只污染）：
     *  必须命中「代码」且至少一个形态列（长期/中期/短期形态）——形态列是自选导出专有，
     *  清仓股/资金股份/历史成交表头均无 → 选错文件时返回空列表，由调用方报「无法识别格式」。</p>
     */
    public static List<WatchlistItem> parseWatchlist(String content) {
        return parseWatchlistDetailed(content).items();
    }

    /**
     * 自选股解析（带「没看懂的行」）——2026-09-13 P2-交易41 同型风险封堵。
     * <p>
     * 导入自选是<b>全量覆盖</b>（saveAll 以文件为准）。原实现 `if (!matches("\\d{6}")) continue`
     * 把非 6 位代码的行（截断行/格式漂移行）静默丢掉、无计数无明细 → <b>丢一行 = 静默删一只自选</b>，
     * 与 2026-09-13 持仓「3 只只进来 2 只」事故同型。此处把丢弃改为如实上报，
     * 由调用方 fail-closed（有看不懂的行就拒绝覆盖）。
     * <p>
     * 判据边界（避免误伤正常文件）：空行 / `#数据来源:通达信` 注释 / 纯分隔线不算「没看懂的行」——
     * 通达信自选导出的行尾就是 `#数据来源:通达信`，没有统计行（2026-09-13 按测试夹具核实）。
     *
     * @return items = 解析成功的条目；unparsed = 表头定位后仍没看懂的行（原始文本，供人话报错）
     */
    public static WatchlistParse parseWatchlistDetailed(String content) {
        List<WatchlistItem> items = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        List<String> lines = split(content);
        int[] col = null;
        for (String line : lines) {
            if (isStructuralLine(line)) continue;
            String[] cells = splitCells(line);
            if (col == null) {
                int[] idx = locate(cells, "代码", "名称", "细分行业", "一二级行业", "长期形态", "中期形态", "短期形态", "近日指标提示");
                if (idx[0] >= 0 && (idx[4] >= 0 || idx[5] >= 0 || idx[6] >= 0)) col = idx;
                continue;
            }
            if (cells.length <= col[0] || !cells[col[0]].trim().matches("\\d{6}")) {
                unparsed.add(line.trim());
                continue;
            }
            items.add(new WatchlistItem(
                    cells[col[0]].trim(),
                    col[1] >= 0 && col[1] < cells.length ? cells[col[1]].trim() : "",
                    col[2] >= 0 && col[2] < cells.length ? cells[col[2]].trim() : "",
                    col[3] >= 0 && col[3] < cells.length ? cells[col[3]].trim() : "",
                    parseIntSafe(col[4], cells),
                    parseIntSafe(col[5], cells),
                    parseIntSafe(col[6], cells),
                    col[7] >= 0 && col[7] < cells.length ? cells[col[7]].trim() : "",
                    LocalDate.now()));
        }
        return new WatchlistParse(items, unparsed);
    }

    /** 自选股解析结果（2026-09-13）：条目 + 没看懂的行（fail-closed 判据）。 */
    public record WatchlistParse(List<WatchlistItem> items, List<String> unparsed) {}

    /**
     * 结构性行（空行 / 前导空白后的 {@code #} 注释 / 纯分隔线 `-` `=` `_` `~` `*`）——不算「没看懂的行」。
     * <p>
     * <b>三条导入链共用同一口径</b>（自选 / 清仓 / 资金明细；P3 补修 2026-10-04）：判据抽到这一处，
     * 免得各链各写一套。此前清仓链只判 {@code line.isEmpty() || line.startsWith("#")}，于是
     * {@code "   "}、{@code "====="}、前导空格的 {@code "  #数据来源:通达信"} 都被当成「没看懂的行」误报；
     * 资金链还漏了 {@code =} 分隔线。**前导空白必须先 trim**——通达信导出常有对齐空格。
     */
    private static boolean isStructuralLine(String line) {
        if (line == null) return true;
        String t = line.trim();
        return t.isEmpty() || t.startsWith("#") || t.matches("^[-=_~*\\s]+$");
    }


    /** 解析清仓股导出 → 已了结交易。
     *  <p>核心列校验（2026-08-27 与自选导入对称）：必须命中「代码」+「介入日期」+「清仓日期」——
     *  三者是清仓股导出专有列，自选/资金/成交表头均缺 → 选错文件返回空列表。</p>
     *  <p>注意这同时是**值级**校验（P2-交易85，2026-10-04）：核心列任一取不到 / 为空 / 不是 yyyyMMdd
     *  的行都不会收进来（详见 {@link #parseSoldWithReport(String)}）——所以本薄包装的返回列表里
     *  不可能出现 {@code buyDate}/{@code sellDate} 为 null 的「半条档案」。</p>
     *  <p>向后兼容薄包装：只取解析成功的交易，丢行明细见 {@link #parseSoldWithReport(String)}
     *  （P2-交易83：调用方要能看到被丢的行就不要再走这里）。</p>
     */
    public static List<SoldTrade> parseSold(String content) {
        return parseSoldWithReport(content).trades();
    }

    /**
     * 清仓股解析（带「没看懂的行」）——2026-10-04 P2-交易83：对齐 P2-交易43 已确立的回执口径。
     * <p>
     * 原实现 `if (cells.length <= col[0] || !cells[col[0]].matches("\\d{6}")) continue;`
     * 静默丢行、方法只返回 {@code List<SoldTrade>}，调用方无从上报——<b>丢一行 = 静默少一只清仓股档案，
     * 用户只看到「导入 42 笔」</b>（与同文件自选/历史成交两条链的口径不一致）。
     * 现在每个被丢弃的行都带<b>行号 + 原文 + 原因</b>上报，由调用方透出到导入回执。
     * <p>
     * <b>行号口径：原文件行号，1 起算（含表头行）</b>——与 {@link #parseHistoricalTradesDetailed(String)}
     * 完全一致（同一条导入链，用户对照导出文件时行号可直接对上）。
     * <p>
     * 判据边界：结构性行（空行 / 前导空白后的 `#` 注释 / 纯分隔线）不算「没看懂的行」——判据与自选、
     * 资金明细两链**共用** {@link #isStructuralLine}（2026-10-04 P3 补修：此前本链只判空行与行首 `#`，
     * 纯分隔线与缩进注释会误报；代码列取值前先 trim，否则 `" 600207"` 会被丢且原因自相矛盾）。
     * <b>不改判定本身</b>——清仓股导入是「按 symbol upsert」而非全量覆盖，丢一行不会删档案，
     * 故不 fail-closed，只如实上报（同资金明细丢行的既有取舍，见 P2-交易45/83）。
     * <p>
     * 但「<b>表头就没认出来</b>」（选错文件 / 空文件，核心列 代码+介入日期+清仓日期 未命中）不是丢行，
     * 而是整份文件不适用——由 {@code headerMatched=false} 如实上报，调用方 fail-closed（2026-10-04 追加 A）。
     * <p>
     * <b>P2-交易85（2026-10-04 对抗审查官实测）：核心列必须真的读到值，缺一即丢行上报。</b>
     * 原实现只校验代码列，列被截断的行走「其余核心列取不到 → parseDateSafe 回 null」的路径，
     * 静默落一条 {@code sellDate=null} 的「半条档案」（实测 {@code 600519\t贵州茅台\t20260101}
     * → {@code trades=1, sellDate=null, unparsed=0}，用户看不到任何提示）。
     * 现在**代码 / 介入日期 / 清仓日期**三列逐一过收录门槛（{@link #coreDateReject}）：
     * <ol>
     *   <li>列取不到（行被截断，cells 不够长）→ 丢行；</li>
     *   <li>值为空 → 丢行；</li>
     *   <li>值不是 yyyyMMdd → 丢行。</li>
     * </ol>
     * 判据依据（为何三列都不放宽、空值也不放过）：清仓股导出 = 已了结交易，A 股 T+1，
     * 每一行必然同时具备介入与清仓日期；仓库内唯一的相关样本（P2-交易43 回归测试的截断行）
     * 其注释亦把「日期列全空」定义为<b>截断/列错位</b>而非合法状态——没有证据支持「空日期合法」。
     * 又 {@code parseDateSafe} 对空串与垃圾值<b>同样返回 null</b>（见其实现），二者在语义上都是
     * 「这一列没读到」，故不区分、一律丢行上报。丢行是比 P2-交易43「字段级保护」更强的保护：
     * 行根本不会进 {@code trades}，既有的日期/天数/涨幅更不可能被覆盖（该服务层保护对**非核心列**
     * 的解析失败仍然生效，如持仓天数/持仓期涨幅% 列坏行）。
     * <p>
     * <b>不改判定本身的边界</b>：结构性行（空行 / 注释 / 分隔线）仍不算丢行；清仓链仍是按 symbol
     * upsert，丢行不 fail-closed，只如实上报（丢行 = 该只清仓档案本次没进库/没更新）。
     *
     * @return trades = 解析成功的清仓记录；unparsedRows = 被丢弃的行（人话一行：第 N 行「原文」：原因）；
     *         headerMatched = 表头是否识别（false → 调用方须拒绝导入）
     */
    public static SoldParse parseSoldWithReport(String content) {
        List<SoldTrade> trades = new ArrayList<>();
        List<String> unparsedRows = new ArrayList<>();
        List<String> lines = split(content);
        int[] col = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (isStructuralLine(line)) continue;
            String[] cells = line.split("\\t");
            if (col == null) {
                int[] idx = locate(cells, "代码", "名称", "介入日期", "清仓日期", "持仓天数", "买卖次数", "持仓期涨幅%");
                if (idx[0] >= 0 && idx[2] >= 0 && idx[3] >= 0) col = idx;
                continue;
            }
            if (cells.length <= col[0]) {
                unparsedRows.add(droppedLine(lineNo, line,
                        "列数不足（代码列第 " + (col[0] + 1) + " 列取不到）"));
                continue;
            }
            if (!cells[col[0]].trim().matches("\\d{6}")) {
                unparsedRows.add(droppedLine(lineNo, line,
                        "代码「" + cells[col[0]].trim() + "」不是 6 位数字"));
                continue;
            }
            // P2-交易85（2026-10-04 对抗审查）：核心日期列同样要有收录门槛——缺一即丢行上报，
            // 不能把「没读到日期」的半条档案静默收进 trades（原实现只有上面那条代码列校验）。
            String buyReject = coreDateReject("介入日期", col[2], cells);
            if (buyReject != null) {
                unparsedRows.add(droppedLine(lineNo, line, buyReject));
                continue;
            }
            String sellReject = coreDateReject("清仓日期", col[3], cells);
            if (sellReject != null) {
                unparsedRows.add(droppedLine(lineNo, line, sellReject));
                continue;
            }
            trades.add(new SoldTrade(
                    cells[col[0]].trim(),
                    col[1] >= 0 && col[1] < cells.length ? cells[col[1]].trim() : "",
                    parseDateSafe(col[2], cells),
                    parseDateSafe(col[3], cells),
                    parseIntSafe(col[4], cells),
                    col[5] >= 0 && col[5] < cells.length ? cells[col[5]].trim() : "",
                    parseDoubleSafe(col[6], cells),
                    "", ""));
        }
        return new SoldParse(trades, unparsedRows, col != null);
    }

    /** 清仓股解析结果（2026-10-04 P2-交易83）：成功记录 + 没看懂的行（行号/原文/原因，人话一行）。
     *  @param headerMatched 表头是否识别（核心列 代码/介入日期/清仓日期 命中）。
     *         {@code false} = 选错文件或空文件——调用方须 fail-closed（对齐资金链
     *         {@code CashQuery.headerMatched()} 口径，2026-10-04 对抗审查 P1：原来静默回 imported=0）
     */
    public record SoldParse(List<SoldTrade> trades, List<String> unparsedRows, boolean headerMatched) {
        public SoldParse {
            if (trades == null) trades = List.of();
            if (unparsedRows == null) unparsedRows = List.of();
        }
    }

    /** 丢行明细人话一行（行号 + 原文 + 原因）——与 {@link UnparsedLine#describe()} 同格式（P2-交易43 口径）。 */
    private static String droppedLine(int lineNo, String raw, String reason) {
        return new UnparsedLine(lineNo, raw == null ? "" : raw.trim(), reason).describe();
    }

    /** 资金股份查询：首行余额/资产 + 明细成本价。 */
    public static CashQuery parseCash(String content) {
        BigDecimalHolder cash = new BigDecimalHolder();
        BigDecimalHolder available = new BigDecimalHolder();
        BigDecimalHolder withdrawable = new BigDecimalHolder();
        BigDecimalHolder marketValue = new BigDecimalHolder();
        BigDecimalHolder assets = new BigDecimalHolder();
        BigDecimalHolder pnl = new BigDecimalHolder();
        List<CashPosition> positions = new ArrayList<>();
        // P2-交易45（2026-09-14）：首行正则命中 ≠ 6 个数值都解析成功——parseNum 失败返回 null，
        // 原来一路写进 AccountSnapshot（资产/现金变 null → 账户卡显示空/异常，且无任何提示）。
        // 现在把「表头命中了但这一项没读成数字」逐项上报，由调用方 fail-closed（缺一个就 400 人话）。
        List<String> headerUnparsed = new ArrayList<>();
        List<String> unparsedRows = new ArrayList<>();
        List<String> lines = split(content);
        Matcher m = CASH_HEAD.matcher(lines.isEmpty() ? "" : lines.get(0));
        boolean headerMatched = m.find();
        if (headerMatched) {
            cash.value = parseNum(m.group(1));
            available.value = parseNum(m.group(2));
            withdrawable.value = parseNum(m.group(3));
            marketValue.value = parseNum(m.group(4));
            assets.value = parseNum(m.group(5));
            pnl.value = parseNum(m.group(6));
            if (cash.value == null) headerUnparsed.add("余额");
            if (available.value == null) headerUnparsed.add("可用");
            if (withdrawable.value == null) headerUnparsed.add("可取");
            if (marketValue.value == null) headerUnparsed.add("参考市值");
            if (assets.value == null) headerUnparsed.add("资产");
            if (pnl.value == null) headerUnparsed.add("盈亏");
        }
        int[] col = null;
        boolean todayPnlColumn = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (isStructuralLine(line)) continue;
            String[] cells = line.split("\\s+"); // 资金明细空格对齐
            if (col == null) {
                int[] idx = locate(cells, "证券代码", "证券名称", "证券数量", "成本价", "当前价", "浮动盈亏", "当日盈亏");
                if (idx[0] >= 0) col = idx;
                // P2-交易37（2026-09-09）：明细是否带「当日盈亏」列——缺列时调用方须保留旧值
                // 而非静默补 0（当日盈亏被晚间导入清零的根因，2026-09-09 生产实测 428→0）
                if (col != null && idx[6] >= 0) todayPnlColumn = true;
                continue;
            }
            if (cells.length <= col[0] || !cells[col[0]].trim().matches("\\d{6}")) {
                // P2-交易45 附带 + P2-交易83（2026-10-04）：明细丢一行 = 某只持仓的「精确成本」不更新
                // （不覆盖数据，故不 fail-closed，但要如实上报）。**原来只丢原文、没有行号，
                // 用户拿着文件对不上是哪一行**——现在与历史成交同口径：行号（原文件行号，1 起算）+ 原文 + 原因。
                String reason = cells.length <= col[0]
                        ? "列数不足（证券代码列第 " + (col[0] + 1) + " 列取不到）"
                        : "证券代码「" + cells[col[0]].trim() + "」不是 6 位数字";
                unparsedRows.add(droppedLine(lineNo, line, reason));
                continue;
            }
            positions.add(new CashPosition(
                    cells[col[0]].trim(),
                    col[1] >= 0 && col[1] < cells.length ? cells[col[1]].trim() : "",
                    parseIntSafe(col[2], cells),
                    parseDoubleSafe(col[3], cells),
                    parseDoubleSafe(col[4], cells),
                    parseDoubleSafe(col[5], cells),
                    parseDoubleSafe(col[6], cells)));
        }
        return new CashQuery(cash.value, available.value, withdrawable.value,
                marketValue.value, assets.value, pnl.value, positions, headerMatched, todayPnlColumn,
                headerUnparsed, unparsedRows);
    }

    // ── 历史成交导入（第五份文件：通达信「历史成交查询」导出，2026-08-18）──

    /**
     * 解析通达信历史成交查询导出 → 逐笔成交行。
     * <p>
     * 列格式（空格对齐）：成交日期 成交时间 证券代码 证券名称 买卖标志 成交数量 成交价格 成交金额
     * 委托编号 成交编号 发生金额 股东代码 [备注]。要点：
     * <ul>
     *   <li>卖出数量为负（-200.00）→ volume 取绝对值 + direction=SELL</li>
     *   <li>数量 0 行（如股息红利税资金下账）保留为 volume=0——调用方计入 nonTrades 不落流水</li>
     *   <li>fee = |发生金额| 与 成交金额 之差（券商实际费用，含佣金/印花税/过户费）</li>
     *   <li>orderId = 成交编号（幂等键）</li>
     * </ul>
     */
    public static List<HistoricalTradeRow> parseHistoricalTrades(String content) {
        return parseHistoricalTradesDetailed(content).rows();
    }

    /**
     * 历史成交解析（带「没看懂的行」）——2026-09-14 P2-交易43 同型风险封堵。
     * <p>
     * 原实现五处 `continue` 静默丢行、响应里没有任何出口：用户只看到「识别出 N 笔」，
     * 不知道同一份文件里还有行被丢了——本链虽不覆盖落盘（append/merge），
     * 但<b>丢一笔真实成交 = 账目缺口只能靠 integrity gaps 事后发现</b>。
     * 现在每处丢弃都带<b>行号 + 原文 + 原因</b>上报，由调用方透出到导入结果。
     * <p>
     * 判据边界：空行 / 纯分隔线（`-` 开头）不算「没看懂的行」。
     * <b>不改判定本身</b>（如「有量无价」是否该放行，缺用户样本前不凭猜改，见 REVIEW P2-交易43）。
     *
     * @return rows = 解析成功的行；unparsed = 被丢弃的行（行号 1 起算，原文，原因）
     */
    public static HistoricalTradeParse parseHistoricalTradesDetailed(String content) {
        List<HistoricalTradeRow> rows = new ArrayList<>();
        List<UnparsedLine> unparsed = new ArrayList<>();
        List<String> lines = split(content);
        int[] col = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (line == null || line.isBlank() || line.startsWith("-")) continue;
            String[] cells = splitCells(line);
            if (col == null) {
                int[] idx = locate(cells, "成交日期", "成交时间", "证券代码", "证券名称", "买卖标志",
                        "成交数量", "成交价格", "成交金额", "成交编号", "发生金额", "备注");
                // 表头需含核心列（成交日期/证券代码/买卖标志/成交编号），否则视为非历史成交导出
                if (idx[0] >= 0 && idx[2] >= 0 && idx[4] >= 0 && idx[8] >= 0) {
                    col = idx;
                } else {
                    return new HistoricalTradeParse(rows, unparsed); // 空 → 调用方报「无法识别格式」
                }
                continue;
            }
            if (cells.length <= col[2] || !cells[col[2]].matches("\\d{6}")) {
                unparsed.add(new UnparsedLine(lineNo, line.trim(), "列数不足或证券代码不是 6 位数字"));
                continue;
            }
            String symbol = cells[col[2]].trim();
            String name = col[3] >= 0 && col[3] < cells.length ? cells[col[3]].trim() : symbol;
            String flag = col[4] >= 0 && col[4] < cells.length ? cells[col[4]].trim() : "";
            TradeDirection direction = switch (flag) {
                case "买入", "买" -> TradeDirection.BUY;
                case "卖出", "卖" -> TradeDirection.SELL;
                default -> null; // 非买卖标志（新股申购/配股等）→ 整行跳过
            };
            if (direction == null) {
                unparsed.add(new UnparsedLine(lineNo, line.trim(),
                        "买卖标志「" + flag + "」不是买入/卖出（新股申购/配股等非二级市场交易）"));
                continue;
            }
            double signedVolume = parseDoubleSafe(col[5], cells);
            int volume = (int) Math.abs(signedVolume);
            // P2-交易43 附带修：原 `parseNum(...).stripTrailingZeros()` 在价格列非数字时
            // parseNum 返回 null → NPE 直接炸整个导入（不是「跳过该行」）；改为先解析再判空。
            BigDecimal price = null;
            if (col[6] >= 0 && col[6] < cells.length) {
                price = parseNum(cells[col[6]]);
                if (price != null) price = price.stripTrailingZeros();
            }
            if (price == null) {
                unparsed.add(new UnparsedLine(lineNo, line.trim(),
                        "成交价格「" + (col[6] >= 0 && col[6] < cells.length ? cells[col[6]].trim() : "") + "」不是数字"));
                continue;
            }
            LocalDate entryDate = parseDateSafe(col[0], cells);
            if (entryDate == null) {
                unparsed.add(new UnparsedLine(lineNo, line.trim(),
                        "成交日期「" + (col[0] >= 0 && col[0] < cells.length ? cells[col[0]].trim() : "")
                                + "」不是 yyyyMMdd 格式"));
                continue;
            }
            // RFC 20260822：成交时间列（HH:mm:ss）→ tradeTime（可空，格式不匹配不阻塞整行）
            LocalTime tradeTime = null;
            if (col[1] >= 0 && col[1] < cells.length && !cells[col[1]].isBlank()) {
                try {
                    tradeTime = LocalTime.parse(cells[col[1]].trim(),
                            DateTimeFormatter.ofPattern("HH:mm:ss"));
                } catch (Exception ignored) {
                    // 时间格式异常 → tradeTime 保持 null（旧文件/导出差异），不丢该笔
                }
            }
            // 数量 0 行（股息红利税/股息入账等资金事件）保留——调用方区分：股息类记账、其余计入 nonTrades
            if (volume == 0) {
                BigDecimal occurred0 = col[9] >= 0 && col[9] < cells.length ? parseNum(cells[col[9]]) : null;
                String remark0 = col[10] >= 0 && col[10] < cells.length ? cells[col[10]].trim() : null;
                rows.add(new HistoricalTradeRow(symbol, name, direction, price, 0, entryDate, tradeTime,
                        null, null, occurred0, remark0));
                continue;
            }
            if (price.signum() <= 0) {
                // P2-交易43：有数量但成交价为 0/负——疑似送股/红股行（无样本前不改判定，但必须可见）
                unparsed.add(new UnparsedLine(lineNo, line.trim(),
                        "有成交数量但成交价为 " + price.toPlainString() + "（疑似送股/红股行）"));
                continue;
            }
            BigDecimal amount = col[7] >= 0 && col[7] < cells.length ? parseNum(cells[col[7]]) : BigDecimal.ZERO;
            BigDecimal occurred = col[9] >= 0 && col[9] < cells.length ? parseNum(cells[col[9]]) : null;
            // fee = |发生金额| 与 成交金额 之差（券商实扣；买入发生金额为负）
            BigDecimal fee = null;
            if (occurred != null && amount.signum() > 0) {
                fee = occurred.abs().subtract(amount).abs();
            }
            String orderId = col[8] >= 0 && col[8] < cells.length ? cells[col[8]].trim() : "";
            String remark = col[10] >= 0 && col[10] < cells.length ? cells[col[10]].trim() : null;
            rows.add(new HistoricalTradeRow(symbol, name, direction, price, volume,
                    entryDate, tradeTime, fee, orderId.isEmpty() ? null : orderId, occurred, remark));
        }
        return new HistoricalTradeParse(rows, unparsed);
    }

    /** 历史成交解析结果（2026-09-14 P2-交易43）：成功行 + 被丢弃行（行号/原文/原因）。 */
    public record HistoricalTradeParse(List<HistoricalTradeRow> rows, List<UnparsedLine> unparsed) {}

    /** 被丢弃的一行（P2-交易43/45 统一结构）：行号 1 起算、原始文本、人话原因。 */
    public record UnparsedLine(int lineNo, String raw, String reason) {
        /** 人话一行（供报错文案/响应透出）。 */
        public String describe() {
            String r = raw != null && raw.length() > 60 ? raw.substring(0, 60) + "…" : (raw != null ? raw : "");
            return "第 " + lineNo + " 行「" + r + "」：" + reason;
        }
    }

    /** 历史成交行（解析后入参，供 {@code importHistoricalTrades} 落流水）。
     *  occurred = 发生金额（源文件原生，买入/下账为负、卖出/入账为正；2026-08-25 加，股息类记账用）；
     *  remark = 备注列（证券买入/证券卖出/股息红利税差异化处理资金下账/股息入账等；2026-08-25 加）。 */
    public record HistoricalTradeRow(String symbol, String name, TradeDirection direction,
                                     BigDecimal price, int volume, LocalDate entryDate, LocalTime tradeTime,
                                     BigDecimal fee, String orderId, BigDecimal occurred, String remark) {}

    /** 是否股息类资金事件（备注含 股息/红利/入账——数量 0 的资金事件，非证券买卖）。
     *  2026-08-25 用户拍板方案 A：股息入账 +现金、红利税 −现金，不进持仓/批次。 */
    public static boolean isDividendEvent(HistoricalTradeRow r) {
        return r.remark() != null && (r.remark().contains("股息") || r.remark().contains("红利")
                || r.remark().contains("入账"));
    }

    // ── 统一入口（R-12「一次把导出的文件交给它就行」· 2026-10-06 trading 重做 ingest 批）──

    /**
     * 导入文件类型（统一入口分派 + 回执展示）。{@code order()} = 内部处理顺序：
     * <b>快照在前、流水在后</b>——用户 2026-10-05 拍板（「钱先于货」，`routine.md` 硬约束）。
     * <p>
     * 为什么不按设计稿 §3① 字面的「事件在前、快照最后」：`routine.md` 实测记录
     * 「顺序反了 → 先补流水、后导快照，会被 auto 模式当『需回放』→ 与快照双计——
     * 2026-09-07 / 09-09 / 09-12 三次事故都是这个形态」（review P2-4 已标记该冲突、未闭环）。
     * 快照先落有两层收益：① cash 先于 positions 是硬依赖（持仓导入的当日盈亏写入闸门②
     * 要求账户快照已存在，见 {@code applyBrokerTodayPnl}）；② 快照把锚定日推到本批日期后，
     * 随后的流水 ≤ 锚定日一律走补录（不重放、不改账），本批内也不会双计。
     * <p>
     * 「资金流水」（银行流水）暂无解析器——无样本不凭空写，归 {@link #UNKNOWN} 如实拒绝（登记待样本）。
     */
    public enum ImportKind {
        CASH("资金股份", 10),
        POSITIONS("持仓股", 20),
        TRADES("历史成交", 30),
        SOLD("清仓股", 40),
        WATCHLIST("自选股", 50),
        UNKNOWN("无法识别", 90);

        private final String label;
        private final int order;

        ImportKind(String label, int order) {
            this.label = label;
            this.order = order;
        }

        /** 人话名称（回执展示「认出来是哪类文件」）。 */
        public String label() {
            return label;
        }

        /** 内部处理顺序权重（小者在前）：资金(10) → 持仓(20) → 成交(30) → 清仓(40) → 自选(50) → 未知(90)。 */
        public int order() {
            return order;
        }
    }

    /**
     * 识别文件类型（统一入口第一步 · 设计 §3①「表头识别 fail-closed」）。
     * <p>
     * 判据与各链既有表头门槛**逐条一致**（资金链首行正则；成交 / 清仓 / 持仓 / 自选各自的核心列），
     * 且**不用文件名**——文件名是用户可改的，表头才是数据自身的事实；认不出返回 {@link ImportKind#UNKNOWN}，
     * 由调用方如实拒绝（不猜、不静默入库）。
     * <p>
     * 扫描顺序即判据优先级：历史成交（4 核心列，最具体）→ 清仓股 → 持仓股 → 自选股。
     * 持仓股判据额外排除资金明细形状（见 {@link #looksLikePositionsHeader}）。
     */
    public static ImportKind detectKind(String content) {
        List<String> lines = split(content);
        if (!lines.isEmpty() && CASH_HEAD.matcher(lines.get(0)).find()) return ImportKind.CASH;
        for (String line : lines) {
            if (isStructuralLine(line)) continue;
            if (line.startsWith("-")) continue; // 历史成交导出的分隔线（与 parseHistoricalTradesDetailed 同口径）
            String[] cells = splitCells(line); // 容忍 tab 与空格对齐两种导出（与各链解析同款）
            if (looksLikeTradesHeader(cells)) return ImportKind.TRADES;
            if (looksLikeSoldHeader(cells)) return ImportKind.SOLD;
            if (looksLikePositionsHeader(cells)) return ImportKind.POSITIONS;
            if (looksLikeWatchlistHeader(cells)) return ImportKind.WATCHLIST;
        }
        return ImportKind.UNKNOWN;
    }

    /** 历史成交表头判据（与 {@link #parseHistoricalTradesDetailed} 的核心列门槛一致）。 */
    private static boolean looksLikeTradesHeader(String[] cells) {
        int[] idx = locate(cells, "成交日期", "证券代码", "买卖标志", "成交编号");
        return idx[0] >= 0 && idx[1] >= 0 && idx[2] >= 0 && idx[3] >= 0;
    }

    /** 清仓股表头判据（与 {@link #parseSoldWithReport} 的核心列门槛一致：代码 + 介入日期 + 清仓日期）。 */
    private static boolean looksLikeSoldHeader(String[] cells) {
        int[] idx = locate(cells, "代码", "介入日期", "清仓日期");
        return idx[0] >= 0 && idx[1] >= 0 && idx[2] >= 0;
    }

    /**
     * 持仓股表头判据（与前端 {@code parseTdxPositions} 的核心列口径一致：代码 + 数量列 + 成本列）。
     * <p>
     * 排除资金明细形状（含「当前价」或「浮动盈亏」）——资金链明细也有 证券代码 / 证券数量 / 成本价 列，
     * 不排除会把资金股份文件抢过来当持仓解析（持仓股导出无这两列，资金明细表头有）。
     */
    private static boolean looksLikePositionsHeader(String[] cells) {
        if (locateFirst(cells, "证券代码", "代码") < 0) return false;
        if (locateFirst(cells, "证券数量", "股票余额", "持仓数量", "数量") < 0) return false;
        if (locateFirst(cells, "成本价", "成本") < 0) return false;
        return locateFirst(cells, "当前价", "浮动盈亏") < 0;
    }

    /** 自选股表头判据（与 {@link #parseWatchlistDetailed} 的核心列门槛一致：代码 + 至少一个形态列）。 */
    private static boolean looksLikeWatchlistHeader(String[] cells) {
        if (locate(cells, "代码")[0] < 0) return false;
        int[] forms = locate(cells, "长期形态", "中期形态", "短期形态");
        return forms[0] >= 0 || forms[1] >= 0 || forms[2] >= 0;
    }

    /**
     * 解析持仓股导出（统一入口 ingest 批，2026-10-06）——语义与前端 {@code parseTdxPositions} 逐条对齐：
     * <ul>
     *   <li>表头核心三列（代码 / 数量 / 成本价）齐才认；不齐 → {@code headerMatched=false}，调用方 fail-closed；</li>
     *   <li>0 股行 → {@code skipped}（券商文件里保留的已清空标的，不是持仓也不是脏数据），
     *       其「当日盈亏」仍计入总额（当日清仓的已实现盈亏也在这一列）；</li>
     *   <li><b>成本价负数合法</b>（反复做 T / 分红摊到 0 以下是真实存在的，实测 600601 方正科技）；</li>
     *   <li>「当日盈亏」有行取不到数 → 总额置 null（绝不发半截总数）；</li>
     *   <li>看不懂的行（列不足 / 代码非六位 / 数量非整数 / 成本非数字）→ {@code unparsedRows} 行号 + 原文 + 原因，
     *       <b>调用方必须拒绝整份导入</b>——持仓导入是全量覆盖，丢一行 = 静默删一只持仓。</li>
     * </ul>
     * 数量读取比前端宽容一档：{@code 1,200} / {@code 1200.00} 这类「整数带小数尾」接受
     * （前端 {@code int.tryParse} 会拒 {@code 1200.00}），非整数（12.5）仍丢行。
     */
    public static PositionParse parsePositions(String content) {
        List<PositionRow> rows = new ArrayList<>();
        List<String> unparsedRows = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> lines = split(content);
        int symbolCol = -1, nameCol = -1, qtyCol = -1, costCol = -1, todayPnlCol = -1, priceCol = -1;
        boolean headerMatched = false;
        BigDecimal todayPnlSum = BigDecimal.ZERO;
        boolean todayPnlBroken = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (isStructuralLine(line)) continue;
            String[] cells = splitCells(line);
            if (!headerMatched) {
                if (!looksLikePositionsHeader(cells)) continue;
                symbolCol = locateFirst(cells, "证券代码", "代码");
                nameCol = locateFirst(cells, "证券名称", "名称");
                qtyCol = locateFirst(cells, "证券数量", "股票余额", "持仓数量", "数量");
                costCol = locateFirst(cells, "成本价", "成本");
                todayPnlCol = locateFirst(cells, "当日盈亏");
                priceCol = locateFirst(cells, "现价", "最新价");
                headerMatched = true;
                continue;
            }
            // 行校验按「实际要读的最大列」判（前端 needMax 同款——行比表头短时不再读到越界列）
            int needMax = Math.max(symbolCol, Math.max(qtyCol, costCol));
            if (cells.length <= needMax) {
                unparsedRows.add(droppedLine(lineNo, line,
                        "字段不足（需要至少 " + (needMax + 1) + " 列，实际 " + cells.length + " 列）"));
                continue;
            }
            String symbol = cells[symbolCol].trim();
            if (!symbol.matches("\\d{6}")) {
                unparsedRows.add(droppedLine(lineNo, line, "代码「" + symbol + "」不是 6 位数字"));
                continue;
            }
            String name = nameCol >= 0 && nameCol < cells.length ? cells[nameCol].trim() : "";
            // 当日盈亏累计放在 0 股判断之前：0 股行的当日盈亏是本日清仓的已实现盈亏，必须计入总额。
            if (todayPnlCol >= 0) {
                BigDecimal t = todayPnlCol < cells.length ? parseNum(cells[todayPnlCol]) : null;
                if (t == null) todayPnlBroken = true;
                else todayPnlSum = todayPnlSum.add(t);
            }
            Integer quantity = integralOf(parseNum(cells[qtyCol]));
            if (quantity == null) {
                unparsedRows.add(droppedLine(lineNo, line,
                        "数量「" + cells[qtyCol].trim() + "」不是整数"));
                continue;
            }
            if (quantity == 0) {
                skipped.add(symbol + " " + name + "（0 股，已清空）");
                continue;
            }
            if (quantity < 0) {
                unparsedRows.add(droppedLine(lineNo, line,
                        "数量「" + quantity + "」为负，不是有效持仓"));
                continue;
            }
            BigDecimal cost = parseNum(cells[costCol]);
            if (cost == null) {
                unparsedRows.add(droppedLine(lineNo, line,
                        "成本价「" + cells[costCol].trim() + "」不是数字"));
                continue;
            }
            // 现价必须 > 0 才有意义（成本价允许为负，现价不允许）——取不到/非正 → null（不带上送）
            BigDecimal price = null;
            if (priceCol >= 0 && priceCol < cells.length) {
                BigDecimal p = parseNum(cells[priceCol]);
                if (p != null && p.signum() > 0) price = p;
            }
            rows.add(new PositionRow(symbol, name, quantity, cost, price));
        }
        BigDecimal todayPnl = todayPnlCol >= 0 && !todayPnlBroken ? todayPnlSum : null;
        return new PositionParse(rows, unparsedRows, skipped, headerMatched, todayPnl);
    }

    /** 持仓股解析结果（2026-10-06 统一入口批）。
     *  @param rows          有效持仓行（数量 > 0）
     *  @param unparsedRows  没看懂的行（每条「第 N 行「原文」：原因」，行号 = 原文件行号 1 起算）——
     *                       <b>非空即须拒绝整份导入</b>（全量覆盖语义：丢一行 = 静默删一只持仓）
     *  @param skipped       看懂但不是持仓的行（0 股残留）——正常导入，只需告知
     *  @param headerMatched 表头是否识别（false = 选错文件 / 空文件，调用方 fail-closed）
     *  @param todayPnl      券商「当日盈亏」列之和（含 0 股行）；缺列或有行取不到数 → null（调用方保留旧值不覆盖） */
    public record PositionParse(List<PositionRow> rows, List<String> unparsedRows, List<String> skipped,
                                boolean headerMatched, BigDecimal todayPnl) {
        public PositionParse {
            if (rows == null) rows = List.of();
            if (unparsedRows == null) unparsedRows = List.of();
            if (skipped == null) skipped = List.of();
        }
    }

    /** 持仓行（券商「持仓股」导出）：{@code avgCost} 可负（做 T/分红摊薄）；
     *  {@code currentPrice} 可空（缺列 / 非数字 / 非正 → null，由导入侧保留原有存储价）。 */
    public record PositionRow(String symbol, String name, int quantity, BigDecimal avgCost,
                              BigDecimal currentPrice) {}

    /**
     * 从文件名提取日期（统一入口给快照两类文件定锚定日；设计 §3①「归一化」）。
     * <p>
     * 通达信导出文件名惯例：`20260904004455_持仓股20260904.txt`（前导时间戳 + 主题 + 日期），
     * 也容忍 `2026-09-04` / `2026_09_04` 分隔写法。取**第一个**完整匹配并按 {@link LocalDate} 逐字段回验
     * ——`20260230` 这类日历上不存在的日期直接跳过（宁可无据，不猜）。
     *
     * @return 文件名里的日期；没有 / 非法 → null（调用方退回既有归一化并标「无据」）
     */
    public static LocalDate parseDateFromFilename(String filename) {
        if (filename == null || filename.isBlank()) return null;
        Matcher m = FILENAME_DATE.matcher(filename);
        while (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (java.time.DateTimeException ignored) {
                // 假日期（20260230 等）→ 继续找下一个匹配（文件名里可能有别的时间戳）
            }
        }
        return null;
    }

    /**
     * 主板代码判据（设计 §11.2「限制**只在账**」）：`600/601/603/605` · `000/001/002/003` 开头才是入账标的；
     * 科创（688）/ 创业（300/301）/ 北交所（8xx/4xx）/ ETF / 可转债 / 港美股一律**如实拒绝、不静默**。
     * <p>注意作用域：只对「账」（持仓 / 成交）生效；`cases` / `watchlist` / 行情**不受限**（§11.2）。
     */
    public static boolean isMainboardCode(String symbol) {
        return symbol != null && symbol.matches("^(600|601|603|605|000|001|002|003)\\d{3}$");
    }

    // ── 工具 ──

    /**
     * 智能分隔（2026-08-25 修复空列吞列）：通达信导出按 tab 分隔且空列保留（如股息行无成交编号）——
     * 行含 tab → split("\t", -1) 保留空列；否则（测试/兼容）按空白分隔。
     * 原 split("\s+") 会吞连续空白 → 空列错位 → 发生金额列解析到股东代码（A000000001 崩）。
     */
    private static String[] splitCells(String line) {
        return line.contains("\t") ? line.split("\t", -1) : line.split("\\s+");
    }

    private static List<String> split(String content) {
        if (content == null) return List.of();
        return List.of(content.split("\\r?\\n"));
    }

    /** 表头定位列索引：返回 [code, name, ...]，未命中的列为 -1。 */
    private static int[] locate(String[] header, String... keys) {
        int[] idx = new int[keys.length];
        for (int i = 0; i < keys.length; i++) idx[i] = -1;
        for (int c = 0; c < header.length; c++) {
            String h = header[c].trim();
            for (int k = 0; k < keys.length; k++) {
                if (idx[k] < 0 && h.contains(keys[k])) idx[k] = c;
            }
        }
        return idx;
    }

    /** 按「键优先级」表头定位（统一入口批，2026-10-06）：返回首个命中的列索引（-1 = 全未命中）。
     *  <p>与 {@link #locate} 的区别：locate 是「列在前者优先」，本方法是「键在前的优先」——
     *  持仓数量列要求「证券数量 / 股票余额」先于兜底的「数量」被找到（列顺序不可控，键顺序可控）。 */
    private static int locateFirst(String[] header, String... keys) {
        for (String key : keys) {
            int idx = locate(header, key)[0];
            if (idx >= 0) return idx;
        }
        return -1;
    }

    /** 数量的整数值读取（统一入口批）：容忍 `1,200` / `1200.00`（券商文件偶见小数尾），
     *  非整数（12.5）/ 超界 → null（按「没读到数量」丢行如实上报，绝不四舍五入猜一个数）。 */
    private static Integer integralOf(BigDecimal v) {
        if (v == null) return null;
        try {
            return v.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static int parseIntSafe(int col, String[] cells) {
        if (col < 0 || col >= cells.length) return 0;
        try {
            return parseNum(cells[col]).intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    private static double parseDoubleSafe(int col, String[] cells) {
        if (col < 0 || col >= cells.length) return 0;
        try {
            return parseNum(cells[col]).doubleValue();
        } catch (Exception e) {
            return 0;
        }
    }

    private static java.math.BigDecimal parseNum(String s) {
        try {
            return new java.math.BigDecimal(s.replaceAll("[,\\s]", ""));
        } catch (NumberFormatException e) {
            return null; // 列错位/脏数据容错（2026-08-25）
        }
    }

    private static LocalDate parseDateSafe(int col, String[] cells) {
        if (col < 0 || col >= cells.length) return null;
        try {
            return LocalDate.parse(cells[col].trim(), TDX_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 清仓股核心日期列的收录门槛（P2-交易85）——{@code null} = 通过；否则返回人话原因（丢行用）。
     * <p>
     * 三级判据，缺一不可：① 列取不到（行被截断，{@code cells} 不够长）→ 丢行；
     * ② trim 后为空 → 丢行；③ 不是 yyyyMMdd → 丢行。
     * <p>
     * 为何用 {@code LocalDate.parse} 而不是复用 {@link #parseDateSafe}：后者把「空串」与「垃圾值」
     * <b>都折叠成 null</b>，无法生成「是空还是格式不对」的人话原因（用户拿到原因才知道怎么补文件）。
     * 判定口径与 {@code parseDateSafe} 完全一致（同一个 {@link #TDX_DATE} formatter）。
     */
    private static String coreDateReject(String column, int col, String[] cells) {
        if (col < 0 || col >= cells.length) {
            return "列数不足（" + column + "列第 " + (col + 1) + " 列取不到）";
        }
        String raw = cells[col].trim();
        if (raw.isEmpty()) {
            return column + "为空（该行没读到日期值，疑似列截断/列错位）";
        }
        try {
            LocalDate.parse(raw, TDX_DATE);
        } catch (Exception e) {
            return column + "「" + raw + "」不是 yyyyMMdd 格式";
        }
        return null;
    }

    private static final class BigDecimalHolder {
        java.math.BigDecimal value = java.math.BigDecimal.ZERO;
    }

    /** 资金明细行（含当日盈亏列）。 */
    public record CashPosition(String symbol, String name, int quantity,
                               double costPrice, double currentPrice, double pnl, double todayPnl) {}

    /** 资金查询结果：首行账户全字段 + 明细。
     *  @param todayPnlColumn 明细表头是否含「当日盈亏」列（缺列 → 调用方不得把当日盈亏清零，P2-交易37）
     *  @param headerUnparsed 首行命中了正则、但这些项没读成数字（P2-交易45：非空 → 调用方必须拒绝导入）
     *  @param unparsedRows   明细里没看懂的行（P2-交易45：不阻塞，但如实上报；
     *                        P2-交易83：每条为「第 N 行「原文」：原因」人话一行，行号 = 原文件行号，1 起算） */
    public record CashQuery(java.math.BigDecimal cash, java.math.BigDecimal available,
                            java.math.BigDecimal withdrawable, java.math.BigDecimal marketValue,
                            java.math.BigDecimal assets, java.math.BigDecimal pnl,
                            List<CashPosition> positions, boolean headerMatched,
                            boolean todayPnlColumn, List<String> headerUnparsed,
                            List<String> unparsedRows) {

        public CashQuery {
            if (headerUnparsed == null) headerUnparsed = List.of();
            if (unparsedRows == null) unparsedRows = List.of();
        }

        /** 旧 10 参构造（无上报字段）——既有测试/调用方兼容。 */
        public CashQuery(java.math.BigDecimal cash, java.math.BigDecimal available,
                         java.math.BigDecimal withdrawable, java.math.BigDecimal marketValue,
                         java.math.BigDecimal assets, java.math.BigDecimal pnl,
                         List<CashPosition> positions, boolean headerMatched, boolean todayPnlColumn) {
            this(cash, available, withdrawable, marketValue, assets, pnl, positions,
                    headerMatched, todayPnlColumn, List.of(), List.of());
        }
    }
}
