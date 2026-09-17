package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.WatchlistItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void parseWatchlist_soldHeader_rejected() {
        // 2026-08-27 事故回归：清仓股表头（无形态列）不得被当作自选解析
        String content = """
                代码\t名称\t涨幅%\t现价\t介入日期\t清仓日期\t持仓天数\t买卖次数\t持仓期涨幅%\t清仓天数\t清仓后涨幅%\t细分行业\t交易代码
                600206\t有研新材\t6.60\t53.65\t20260731\t20260826\t26\t6+2\t32.45\t1\t6.60\t半导体\t600206
                #数据来源:通达信
                """;
        assertTrue(TradingImportParser.parseWatchlist(content).isEmpty(), "清仓股表头 → 自选解析必须拒绝");
    }

    @Test
    void parseWatchlist_cashHeader_rejected() {
        // 资金股份查询表头（证券代码列 + 无形态列）不得被当作自选解析
        String content = """
                编号\t证券代码\t证券名称\t证券数量\t可卖数量\t成本价\t当前价
                1\t600809\t山西汾酒\t100.00\t100.00\t122.3849\t123.5200
                """;
        assertTrue(TradingImportParser.parseWatchlist(content).isEmpty(), "资金股份表头 → 自选解析必须拒绝");
    }

    @Test
    void parseWatchlistDetailed_reportsUnparsedRows() {
        // 2026-09-13（P2-交易41 同型风险）：自选导入是全量覆盖，原来没看懂的行被 `continue` 静默丢掉
        // →「丢一行 = 静默删一只自选」。改为如实上报，由调用方 fail-closed（拒绝覆盖）。
        String content = """
                代码\t名称\t细分行业\t一二级行业\t长期形态\t中期形态\t短期形态\t近日指标提示
                000725\t京东方Ａ\t元器件\t信息产业-元器件\t6\t8\t1\tKDJ死叉
                \t列错位行\t元器件\t信息产业-元器件\t6\t8\t1\tKDJ死叉
                6004\t截断代码\t证券\t金融-证券\t2\t10\t1\tKDJ死叉
                #数据来源:通达信
                """;
        TradingImportParser.WatchlistParse p = TradingImportParser.parseWatchlistDetailed(content);
        assertEquals(1, p.items().size());
        assertEquals("000725", p.items().get(0).symbol());
        assertEquals(2, p.unparsed().size(), "没看懂的行必须如实上报，不能静默丢");
        assertTrue(p.unparsed().get(0).contains("列错位行"), "原样保留整行供人话报错：" + p.unparsed().get(0));
        assertTrue(p.unparsed().get(1).contains("截断代码"));
    }

    @Test
    void parseWatchlist_structuralLines_notCountedAsUnparsed() {
        // 判据边界（防误伤正常文件）：#数据来源注释 / 空行 / 纯分隔线属结构性行。
        // 若把它们算作「没看懂的行」，每次正常导入都会被 fail-closed 拒掉——比原 bug 更糟。
        String content = """
                代码\t名称\t细分行业\t一二级行业\t长期形态\t中期形态\t短期形态\t近日指标提示
                000725\t京东方Ａ\t元器件\t信息产业-元器件\t6\t8\t1\tKDJ死叉
                #数据来源:通达信

                ----------
                """;
        TradingImportParser.WatchlistParse p = TradingImportParser.parseWatchlistDetailed(content);
        assertEquals(1, p.items().size());
        assertTrue(p.unparsed().isEmpty(), "结构性行不算没看懂的行，实际: " + p.unparsed());
    }

    @Test
    void parseSold_watchlistHeader_rejected() {
        // 对称校验：自选表头（无介入/清仓日期）不得被当作清仓股解析
        String content = """
                代码\t名称\t量比\t主买净额\t涨幅%\t细分行业\t一二级行业\t长期形态\t中期形态\t短期形态\t近日指标提示
                000725\t京东方Ａ\t0.80\t0.00\t-0.85\t元器件\t信息产业-元器件\t6\t8\t1\tKDJ死叉
                """;
        assertTrue(TradingImportParser.parseSold(content).isEmpty(), "自选表头 → 清仓解析必须拒绝");
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
                1           600809          山西汾酒        100.00          100.00          122.3849        123.5200        12352.00        0.00            0.00            113.44          0.927              A000000000
                2           000725          京东方Ａ        5300.00         5300.00         6.0421          5.8100          30793.00        0.00            0.00            -1229.57        -3.841             A000000000
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
                20260803        14:52:56        600206          有研新材        卖出            -200.00         33.12000000         6624.00         151117          69351117                6620.05          A000000000        证券卖出
                20260803        14:53:51        002428          云南锗业        买入            400.00          68.14000000         27256.00        151747          0101000075800458        -27258.33        A000000000        证券买入
                20260818        00:00:00        000725          京东方Ａ        买入            0.00            0.00000000          10.08           0                                       -10.08           A000000000        股息红利税差异化处理资金下账
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

    // ── 2026-09-14 P2-交易43/45：解析层「被丢弃的行」必须可见（行号 + 原因）──

    @Test
    void parseHistoricalTradesDetailed_reportsEveryDroppedRowWithLineNumberAndReason() {
        // 每行一个坏法（除第 2 行正常）：行号 1 起算，含表头行
        String content = String.join("\n",
                String.join("\t", "成交日期", "成交时间", "证券代码", "证券名称", "买卖标志",
                        "成交数量", "成交价格", "成交金额", "成交编号", "发生金额", "备注"),
                String.join("\t", "20260803", "14:52:56", "600206", "有研新材", "卖出",
                        "-200.00", "33.12000000", "6624.00", "69351117", "6620.05", "证券卖出"),
                String.join("\t", "2026080X", "14:53:51", "002428", "坏日期", "买入",
                        "400.00", "68.14000000", "27256.00", "0101000075800458", "-27258.33", "证券买入"),
                String.join("\t", "20260803", "14:53:51", "002428", "坏价格", "买入",
                        "400.00", "--", "27256.00", "0101000075800459", "-27258.33", "证券买入"),
                String.join("\t", "20260803", "14:53:51", "732448", "天博申购", "新股申购",
                        "500.00", "10.00000000", "5000.00", "0101000075800460", "-5000.00", "新股申购"),
                String.join("\t", "20260803", "14:53:51", "600601", "送股", "买入",
                        "100.00", "0.00000000", "0.00", "0101000075800461", "0.00", "红股入账"),
                String.join("\t", "20260803", "14:53:51", "60021", "代码坏", "买入",
                        "100.00", "10.00000000", "1000.00", "0101000075800462", "-1000.00", "证券买入"));

        TradingImportParser.HistoricalTradeParse p = TradingImportParser.parseHistoricalTradesDetailed(content);

        assertEquals(1, p.rows().size(), "只有第 2 行是能认的成交");
        assertEquals(5, p.unparsed().size(), "另外 5 行必须如实上报（原来全部静默 continue）");
        assertEquals(3, p.unparsed().get(0).lineNo());
        assertTrue(p.unparsed().get(0).reason().contains("成交日期"), p.unparsed().get(0).reason());
        assertTrue(p.unparsed().get(1).reason().contains("成交价格"), p.unparsed().get(1).reason());
        assertTrue(p.unparsed().get(2).reason().contains("买卖标志"), p.unparsed().get(2).reason());
        assertTrue(p.unparsed().get(3).reason().contains("送股"), p.unparsed().get(3).reason());
        assertTrue(p.unparsed().get(4).reason().contains("6 位"), p.unparsed().get(4).reason());
        // describe() 带行号与原文，供响应/报错直接展示
        assertTrue(p.unparsed().get(0).describe().contains("第 3 行"));
    }

    @Test
    void parseHistoricalTrades_priceNotNumber_doesNotThrow() {
        // P2-交易43 附带修：原 `parseNum(...).stripTrailingZeros()` 在价格列非数字时 NPE 炸整个导入
        String content = String.join("\n",
                String.join("\t", "成交日期", "证券代码", "买卖标志", "成交数量", "成交价格", "成交编号"),
                String.join("\t", "20260803", "600206", "买入", "100.00", "不是数字", "1"));
        TradingImportParser.HistoricalTradeParse p = TradingImportParser.parseHistoricalTradesDetailed(content);
        assertTrue(p.rows().isEmpty());
        assertEquals(1, p.unparsed().size(), "非数字价格要被记为丢弃行，而不是抛 NPE");
    }

    @Test
    void parseCash_headerMatchedButValueUnparsable_reportsFieldNames() {
        // P2-交易45：首行正则命中（1.2.3 落在 [\d,.]+ 内）但 parseNum 失败 → 原来 null 一路写进账户快照
        String content = "人民币: 余额:1.2.3  可用:1000.00  可取:500.00  参考市值:2000.00  资产:3000.00  盈亏:10.00\n"
                + "证券代码 证券名称 证券数量 成本价 当前价 浮动盈亏\n"
                + "600206 有研新材 900 46.012 50.0 100.0\n";
        TradingImportParser.CashQuery q = TradingImportParser.parseCash(content);
        assertTrue(q.headerMatched(), "正则本身命中");
        assertEquals(1, q.headerUnparsed().size());
        assertTrue(q.headerUnparsed().contains("余额"), "要指名是哪一项没读成数字：" + q.headerUnparsed());
        assertNull(q.cash(), "该字段确为 null——正是必须 fail-closed 的原因");
    }

    @Test
    void parseCash_unparsedDetailRows_reported() {
        String content = "人民币: 余额:1.00  可用:1.00  可取:1.00  参考市值:1.00  资产:1.00  盈亏:1.00\n"
                + "证券代码 证券名称 证券数量 成本价 当前价 浮动盈亏\n"
                + "600206 有研新材 900 46.012 50.0 100.0\n"
                + "这不是明细行 xxx\n";
        TradingImportParser.CashQuery q = TradingImportParser.parseCash(content);
        assertTrue(q.headerUnparsed().isEmpty());
        assertEquals(1, q.positions().size());
        assertEquals(1, q.unparsedRows().size(), "没看懂的明细行要上报（丢一行 = 该只精确成本不更新）");
    }


    @Test
    void isNonTradableCode_identifiesPlaceholderSegments() {
        // 2026-08-25 用户反馈：明显非股票代码（通达信占位段）识别
        assertTrue(TradingImportParser.isNonTradableCode("799999"), "799999 登记指定 → 非可交易占位");
        assertTrue(TradingImportParser.isNonTradableCode("800001"), "80 段占位");
        assertTrue(TradingImportParser.isNonTradableCode("819999"), "81 段占位");
        assertFalse(TradingImportParser.isNonTradableCode("600519"), "沪市股票不误伤");
        assertFalse(TradingImportParser.isNonTradableCode("000725"), "深市股票不误伤");
        assertFalse(TradingImportParser.isNonTradableCode("512690"), "场内基金（5 开头）不误伤");
        assertFalse(TradingImportParser.isNonTradableCode("12345"), "非 6 位不匹配");
        assertFalse(TradingImportParser.isNonTradableCode(null));
    }
}

