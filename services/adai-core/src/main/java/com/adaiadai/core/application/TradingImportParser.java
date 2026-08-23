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

    /** 解析自选股导出 → 自选条目（表头定位列）。 */
    public static List<WatchlistItem> parseWatchlist(String content) {
        List<WatchlistItem> items = new ArrayList<>();
        List<String> lines = split(content);
        int[] col = null;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] cells = line.split("\\t");
            if (col == null) {
                int[] idx = locate(cells, "代码", "名称", "细分行业", "一二级行业", "长期形态", "中期形态", "短期形态", "近日指标提示");
                if (idx[0] >= 0) col = idx;
                continue;
            }
            if (cells.length <= col[0] || !cells[col[0]].matches("\\d{6}")) continue;
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
        return items;
    }

    /** 解析清仓股导出 → 已了结交易。 */
    public static List<SoldTrade> parseSold(String content) {
        List<SoldTrade> trades = new ArrayList<>();
        List<String> lines = split(content);
        int[] col = null;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] cells = line.split("\\t");
            if (col == null) {
                int[] idx = locate(cells, "代码", "名称", "介入日期", "清仓日期", "持仓天数", "买卖次数", "持仓期涨幅%");
                if (idx[0] >= 0) col = idx;
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
        }
        int[] col = null;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) continue;
            String[] cells = line.split("\\s+"); // 资金明细空格对齐
            if (col == null) {
                int[] idx = locate(cells, "证券代码", "证券名称", "证券数量", "成本价", "当前价", "浮动盈亏", "当日盈亏");
                if (idx[0] >= 0) col = idx;
                continue;
            }
            if (cells.length <= col[0] || !cells[col[0]].matches("\\d{6}")) continue;
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
                marketValue.value, assets.value, pnl.value, positions, headerMatched);
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
        List<HistoricalTradeRow> rows = new ArrayList<>();
        List<String> lines = split(content);
        int[] col = null;
        for (String line : lines) {
            if (line == null || line.isBlank() || line.startsWith("-")) continue;
            String[] cells = line.split("\\s+");
            if (col == null) {
                int[] idx = locate(cells, "成交日期", "成交时间", "证券代码", "证券名称", "买卖标志",
                        "成交数量", "成交价格", "成交金额", "成交编号", "发生金额");
                // 表头需含核心列（成交日期/证券代码/买卖标志/成交编号），否则视为非历史成交导出
                if (idx[0] >= 0 && idx[2] >= 0 && idx[4] >= 0 && idx[8] >= 0) {
                    col = idx;
                } else {
                    return rows; // 空列表 → 调用方报「无法识别格式」
                }
                continue;
            }
            if (cells.length <= col[2] || !cells[col[2]].matches("\\d{6}")) continue;
            String symbol = cells[col[2]].trim();
            String name = col[3] >= 0 && col[3] < cells.length ? cells[col[3]].trim() : symbol;
            String flag = col[4] >= 0 && col[4] < cells.length ? cells[col[4]].trim() : "";
            TradeDirection direction = switch (flag) {
                case "买入", "买" -> TradeDirection.BUY;
                case "卖出", "卖" -> TradeDirection.SELL;
                default -> null; // 非买卖标志（新股申购/配股等）→ 整行跳过
            };
            if (direction == null) continue;
            double signedVolume = parseDoubleSafe(col[5], cells);
            int volume = (int) Math.abs(signedVolume);
            BigDecimal price = col[6] >= 0 && col[6] < cells.length
                    ? parseNum(cells[col[6]]).stripTrailingZeros() : null;
            if (price == null) continue;
            LocalDate entryDate = parseDateSafe(col[0], cells);
            if (entryDate == null) continue;
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
            // 数量 0 行（股息红利税等非交易资金事件）保留——调用方计入 nonTrades 不落流水
            if (volume == 0) {
                rows.add(new HistoricalTradeRow(symbol, name, direction, price, 0, entryDate, tradeTime, null, null));
                continue;
            }
            if (price.signum() <= 0) continue; // 有数量但无价格 → 数据异常跳过
            BigDecimal amount = col[7] >= 0 && col[7] < cells.length ? parseNum(cells[col[7]]) : BigDecimal.ZERO;
            BigDecimal occurred = col[9] >= 0 && col[9] < cells.length ? parseNum(cells[col[9]]) : null;
            // fee = |发生金额| 与 成交金额 之差（券商实扣；买入发生金额为负）
            BigDecimal fee = null;
            if (occurred != null && amount.signum() > 0) {
                fee = occurred.abs().subtract(amount).abs();
            }
            String orderId = col[8] >= 0 && col[8] < cells.length ? cells[col[8]].trim() : "";
            rows.add(new HistoricalTradeRow(symbol, name, direction, price, volume,
                    entryDate, tradeTime, fee, orderId.isEmpty() ? null : orderId));
        }
        return rows;
    }

    /** 历史成交行（解析后入参，供 {@code importHistoricalTrades} 落流水）。 */
    public record HistoricalTradeRow(String symbol, String name, TradeDirection direction,
                                     BigDecimal price, int volume, LocalDate entryDate, LocalTime tradeTime,
                                     BigDecimal fee, String orderId) {}

    // ── 工具 ──

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
        return new java.math.BigDecimal(s.replaceAll("[,\\s]", ""));
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

    /** 资金查询结果：首行账户全字段 + 明细。 */
    public record CashQuery(java.math.BigDecimal cash, java.math.BigDecimal available,
                            java.math.BigDecimal withdrawable, java.math.BigDecimal marketValue,
                            java.math.BigDecimal assets, java.math.BigDecimal pnl,
                            List<CashPosition> positions, boolean headerMatched) {}
}
