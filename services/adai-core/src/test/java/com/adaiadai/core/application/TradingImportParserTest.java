package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.WatchlistItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingImportParser — 通达信三种导出格式解析测试（真实表头/数据片段）。
 */
class TradingImportParserTest {

    @Test
    void parseWatchlist_realHeader() {
        String content = """
                代码\t名称\t量比\t主买净额\t涨幅%\t细分行业\t一二级行业\t主营构成\t地区\t3日涨幅%\t贝塔系数\t流通市值\t主力净额\t开盘金额\t长期形态\t中期形态\t短期形态\t近日指标提示\t换手%\t毛利率%\t未匹配量\t强弱度%
                000725\t京东方Ａ\t0.80\t0.00\t-0.85\t元器件\t信息产业-元器件\t显示器件业务\t北京\t-1.69\t1.38\t2054.83亿\t0.00\t6139.69\t6\t8\t1\tKDJ死叉\t3.56\t15.60\t--\t-1.31
                601066\t中信建投\t0.98\t0.00\t-1.66\t证券\t金融-证券\t交易及机构客户服务业务\t北京\t-0.67\t1.11\t1658.99亿\t0.00\t485.23\t2\t10\t1\tKDJ死叉\t0.36\t60.64\t--\t-1.66
                #数据来源:通达信
                """;
        List<WatchlistItem> items = TradingImportParser.parseWatchlist(content);
        assertEquals(2, items.size());
        WatchlistItem first = items.get(0);
        assertEquals("000725", first.symbol());
        assertEquals("京东方Ａ", first.name());
        assertEquals("元器件", first.industry());
        assertEquals("信息产业-元器件", first.industry2());
        assertEquals(6, first.longForm());
        assertEquals(8, first.midForm());
        assertEquals(1, first.shortForm());
        assertEquals("KDJ死叉", first.signal());
    }

    @Test
    void parseSold_realHeader() {
        String content = """
                代码\t名称\t涨幅%\t现价\t介入日期\t清仓日期\t持仓天数\t买卖次数\t持仓期涨幅%\t清仓天数\t清仓后涨幅%
                600206\t有研新材\t1.14\t50.78\t20260731\t20260803\t3\t1+1\t-12.82\t11\t53.32
                600584\t长电科技\t1.14\t78.71\t20260722\t20260803\t12\t5+1\t-29.22\t11\t29.20
                #数据来源:通达信
                """;
        List<SoldTrade> trades = TradingImportParser.parseSold(content);
        assertEquals(2, trades.size());
        SoldTrade first = trades.get(0);
        assertEquals("600206", first.symbol());
        assertEquals("有研新材", first.name());
        assertEquals("2026-07-31", first.buyDate().toString());
        assertEquals("2026-08-03", first.sellDate().toString());
        assertEquals(3, first.holdDays());
        assertEquals("1+1", first.tradeCount());
        assertTrue(Math.abs(first.holdPnlPct() - (-12.82)) < 0.001);
    }

    @Test
    void parseCash_realHeader() {
        String content = """
                人民币: 余额:292.88  可用:292.88  可取:292.88  参考市值:110212.00  资产:110504.88  盈亏:15235.55
                -------------------------------------------------------------------------------------------------------
                编号        证券代码        证券名称        证券数量        可卖数量        成本价          当前价          最新市值        今买数量        今卖数量        浮动盈亏        盈亏比例(%)        股东代码
                1           600809          山西汾酒        100.00          100.00          122.3849        123.5200        12352.00        0.00            0.00            113.44          0.927              A511358384
                2           000725          京东方Ａ        5300.00         5300.00         6.0421          5.8100          30793.00        0.00            0.00            -1229.57        -3.841             0903874313
                """;
        TradingImportParser.CashQuery q = TradingImportParser.parseCash(content);
        assertEquals(0, q.cash().compareTo(new BigDecimal("292.88")));
        assertEquals(0, q.assets().compareTo(new BigDecimal("110504.88")));
        assertEquals(2, q.positions().size());
        assertEquals("600809", q.positions().get(0).symbol());
        assertTrue(Math.abs(q.positions().get(0).costPrice() - 122.3849) < 0.0001);
        assertEquals("000725", q.positions().get(1).symbol());
        assertTrue(Math.abs(q.positions().get(1).costPrice() - 6.0421) < 0.0001);
    }

    @Test
    void parseHistoricalTrades_realHeader() {
        // 真实通达信「历史成交查询」导出片段：卖出数量为负、价格 8 位小数、含成交编号/发生金额
        String content = """
                -------------------------------------------------------------------------------------------------------

                成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码          备注
                20260803        14:52:56        600206          有研新材        卖出            -200.00         33.12000000         6624.00         151117          69351117                6620.05          A511358384        证券卖出
                20260803        14:53:51        002428          云南锗业        买入            400.00          68.14000000         27256.00        151747          0101000075800458        -27258.33        0903874313        证券买入
                20260818        00:00:00        000725          京东方Ａ        买入            0.00            0.00000000          10.08           0                                       -10.08           0903874313        股息红利税差异化处理资金下账
                """;
        List<TradingImportParser.HistoricalTradeRow> rows = TradingImportParser.parseHistoricalTrades(content);
        assertEquals(3, rows.size(), "2 笔真实成交 + 1 行数量 0 的非交易事件（股息红利税）");
        TradingImportParser.HistoricalTradeRow sell = rows.get(0);
        assertEquals("600206", sell.symbol());
        assertEquals("有研新材", sell.name());
        assertEquals(TradeDirection.SELL, sell.direction());
        assertEquals(200, sell.volume(), "卖出数量取绝对值");
        assertEquals("2026-08-03", sell.entryDate().toString());
        assertEquals(java.time.LocalTime.of(14, 52, 56), sell.tradeTime(), "RFC 20260822：成交时间列解析");
        assertEquals("69351117", sell.orderId());
        // fee = |发生金额 - 成交金额| = 6624.00 - 6620.05 = 3.95
        assertEquals(0, sell.fee().compareTo(new BigDecimal("3.95")));
        TradingImportParser.HistoricalTradeRow buy = rows.get(1);
        assertEquals(TradeDirection.BUY, buy.direction());
        assertEquals(400, buy.volume());
        // 买入发生金额为负：fee = |27256.00| - |-27258.33| 之差 = 2.33
        assertEquals(0, buy.fee().compareTo(new BigDecimal("2.33")));
        assertEquals("0101000075800458", buy.orderId());
        // 数量 0 非交易行：volume=0，供导入方计入 nonTrades
        assertEquals(0, rows.get(2).volume());
        assertEquals("000725", rows.get(2).symbol());
    }

    @Test
    void parseHistoricalTrades_wrongFormat_returnsEmpty() {
        String content = """
                代码\t名称\t涨幅%\t现价\t成本价\t证券数量
                000725\t京东方Ａ\t6.41\t6.47\t6.203\t4800
                """;
        assertTrue(TradingImportParser.parseHistoricalTrades(content).isEmpty());
    }
}
