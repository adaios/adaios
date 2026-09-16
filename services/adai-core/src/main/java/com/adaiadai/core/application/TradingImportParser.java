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
            if (isSkippableLine(line)) continue;
            String[] cells = splitCells(line);
            if (col == null) {
                int[] idx = locate(cells, "代码", "名称", "细分行业", "一二级行业", "长期形态", "中期形态", "短期形态", "近日指标提示");
                if (idx[0] >= 0 && (idx[4] >= 0 || idx[5] >= 0 || idx[6] >= 0)) col = idx;
                continue;
            }
            if (cells.length <= col[0] || !cells[col[0]].matches("\\d{6}")) {
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

    /** 结构性行（空行 / `#` 注释 / 纯分隔线）——不算「没看懂的行」（2026-09-13 fail-closed 判据）。 */
    private static boolean isSkippableLine(String line) {
        if (line == null) return true;
        String t = line.trim();
        return t.isEmpty() || t.startsWith("#") || t.matches("^[-=_~*\\s]+$");
    }


    /** 解析清仓股导出 → 已了结交易。
     *  <p>核心列校验（2026-08-27 与自选导入对称）：必须命中「代码」+「介入日期」+「清仓日期」——
     *  三者是清仓股导出专有列，自选/资金/成交表头均缺 → 选错文件返回空列表。</p>
     */
    public static List<SoldTrade> parseSold(String content) {
        List<SoldTrade> trades = new ArrayList<>();
        List<String> lines = split(content);
        int[] col = null;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] cells = line.split("\\t");
            if (col == null) {
                int[] idx = locate(cells, "代码", "名称", "介入日期", "清仓日期", "持仓天数", "买卖次数", "持仓期涨幅%");
                if (idx[0] >= 0 && idx[2] >= 0 && idx[3] >= 0) col = idx;
                continue;
            }
            if (cells.length <= col[0] || !cells[col[0]].matches("\\d{6}")) continue;
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
        return trades;
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
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) continue;
            String[] cells = line.split("\\s+"); // 资金明细空格对齐
            if (col == null) {
                int[] idx = locate(cells, "证券代码", "证券名称", "证券数量", "成本价", "当前价", "浮动盈亏", "当日盈亏");
                if (idx[0] >= 0) col = idx;
                // P2-交易37（2026-09-09）：明细是否带「当日盈亏」列——缺列时调用方须保留旧值
                // 而非静默补 0（当日盈亏被晚间导入清零的根因，2026-09-09 生产实测 428→0）
                if (col != null && idx[6] >= 0) todayPnlColumn = true;
                continue;
            }
            if (cells.length <= col[0] || !cells[col[0]].matches("\\d{6}")) {
                // P2-交易45 附带：明细丢一行 = 某只持仓的「精确成本」不更新（不覆盖数据，故不 fail-closed，
                // 但要如实上报——原来静默 continue，用户以为全部更新了）
                unparsedRows.add(line.trim());
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

    private static final class BigDecimalHolder {
        java.math.BigDecimal value = java.math.BigDecimal.ZERO;
    }

    /** 资金明细行（含当日盈亏列）。 */
    public record CashPosition(String symbol, String name, int quantity,
                               double costPrice, double currentPrice, double pnl, double todayPnl) {}

    /** 资金查询结果：首行账户全字段 + 明细。
     *  @param todayPnlColumn 明细表头是否含「当日盈亏」列（缺列 → 调用方不得把当日盈亏清零，P2-交易37）
     *  @param headerUnparsed 首行命中了正则、但这些项没读成数字（P2-交易45：非空 → 调用方必须拒绝导入）
     *  @param unparsedRows   明细里没看懂的行（P2-交易45：不阻塞，但如实上报） */
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
