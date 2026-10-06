package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.WatchlistItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        assertTrue(TradingImportParser.parseSoldWithReport(content).unparsedRows().isEmpty(),
                "表头认不出 = 选错文件（不是丢行），不得报成没看懂的行");
        // 2026-10-04 追加 A：选错文件必须能被调用方识别出来（headerMatched=false → fail-closed 400），
        // 不能再像原来那样 trades=0 + unparsed=0 一路静默回 imported=0。
        assertFalse(TradingImportParser.parseSoldWithReport(content).headerMatched(),
                "自选表头 → 清仓链 headerMatched 必须为 false");
        assertFalse(TradingImportParser.parseSoldWithReport("").headerMatched(),
                "空文件同样 headerMatched=false（与资金链对空文件的处理一致）");
    }

    // ── 2026-10-04 P2-交易83：清仓股丢行必须可见（行号 + 原文 + 原因），对齐 P2-交易43 ──

    @Test
    void parseSoldWithReport_reportsDroppedRowWithLineNumberRawAndReason() {
        // 第 2 行正常；第 3 行代码被截断成 5 位（真实事故形态）；第 4 行是注释（结构性行，不算丢行）
        String content = String.join("\n",
                String.join("\t", "代码", "名称", "介入日期", "清仓日期", "持仓天数", "买卖次数", "持仓期涨幅%"),
                String.join("\t", "600206", "有研新材", "20260731", "20260803", "3", "1+1", "-12.82"),
                String.join("\t", "60021", "截断代码", "20260731", "20260803", "3", "1+1", "-12.82"),
                "#数据来源:通达信");

        TradingImportParser.SoldParse p = TradingImportParser.parseSoldWithReport(content);

        assertEquals(1, p.trades().size(), "正常行不受影响");
        assertEquals("600206", p.trades().get(0).symbol());
        assertEquals(3, p.trades().get(0).holdDays());
        assertEquals(1, p.unparsedRows().size(), "被 continue 掉的行走丢了必须如实上报（原来静默）");
        String dropped = p.unparsedRows().get(0);
        assertTrue(dropped.startsWith("第 3 行"), "行号 = 原文件行号（1 起算，含表头行）：" + dropped);
        assertTrue(dropped.contains("60021"), "原文要带上，用户能对上文件：" + dropped);
        assertTrue(dropped.contains("6 位数字"), "原因要写清（代码不是 6 位数字）：" + dropped);
    }

    @Test
    void parseSoldWithReport_shortRow_reportsColumnShortageWithoutKillingOtherRows() {
        // 表头带前置「序号」列 → 代码列在第 2 列：只有 1 列的行取不到代码 → 「列数不足」
        String content = String.join("\n",
                String.join("\t", "序号", "代码", "名称", "介入日期", "清仓日期", "持仓天数", "买卖次数", "持仓期涨幅%"),
                String.join("\t", "1", "600584", "长电科技", "20260722", "20260803", "12", "5+1", "-29.22"),
                "2");

        TradingImportParser.SoldParse p = TradingImportParser.parseSoldWithReport(content);

        assertEquals(1, p.trades().size());
        assertEquals("600584", p.trades().get(0).symbol());
        assertEquals(1, p.unparsedRows().size());
        String dropped = p.unparsedRows().get(0);
        assertTrue(dropped.startsWith("第 3 行"), dropped);
        assertTrue(dropped.contains("列数不足"), dropped);
        assertTrue(dropped.contains("第 2 列"), "要说清是哪一列取不到：" + dropped);
    }

    // ── 2026-10-04 P3：丢行判据与自选链口径统一（结构性行不误报 + 代码列先 trim） ──

    @Test
    void parseSoldWithReport_structuralLines_notCountedAsUnparsed() {
        // 审查官实测：清仓链原先只判 `line.isEmpty() || line.startsWith("#")`，
        // 于是 "   " / "=====" / 前导空格的 "  #数据来源:通达信" 全被误报成「没看懂的行」。
        // 同文件自选链用结构性行判据（trim + ^[-=_~\s]+$）已正确排除 → 三链必须同口径。
        String content = String.join("\n",
                String.join("\t", "代码", "名称", "介入日期", "清仓日期", "持仓天数", "买卖次数", "持仓期涨幅%"),
                String.join("\t", "600206", "有研新材", "20260731", "20260803", "3", "1+1", "-12.82"),
                "   ",
                "=====",
                "  #数据来源:通达信");

        TradingImportParser.SoldParse p = TradingImportParser.parseSoldWithReport(content);

        assertEquals(1, p.trades().size(), "正常行照常解析");
        assertTrue(p.unparsedRows().isEmpty(),
                "结构性行（空白行 / 分隔线 / 缩进注释）不算丢行：" + p.unparsedRows());
    }

    @Test
    void parseSoldWithReport_codeCellWithSpaces_parsesInsteadOfDropping() {
        // 代码列带前导空格（真实导出对齐空格）：旧实现直接 matches("\d{6}") → 被丢，
        // 且原因写成「代码「600207」不是 6 位数字」——trim 后明明合规，原因自相矛盾。
        String content = String.join("\n",
                String.join("\t", "代码", "名称", "介入日期", "清仓日期", "持仓天数", "买卖次数", "持仓期涨幅%"),
                " 600207\t有研新材\t20260731\t20260803\t3\t1+1\t-12.82");

        TradingImportParser.SoldParse p = TradingImportParser.parseSoldWithReport(content);

        assertEquals(1, p.trades().size(), "代码列带空格的行必须能正常解析，不能丢");
        assertEquals("600207", p.trades().get(0).symbol());
        assertTrue(p.unparsedRows().isEmpty(), "trim 后合规 → 不得报丢行：" + p.unparsedRows());
    }

    // ── 2026-10-04 P2-交易85：核心列（代码/介入日期/清仓日期）缺一即丢行上报，不得静默落半条档案 ──

    @Test
    void parseSoldWithReport_truncatedRow_reportedInsteadOfLandingHalfArchive() {
        // 审查官实测复现：`600519\t贵州茅台\t20260101`（列被截断、无清仓日期列）
        // 原实现 → trades=1, sellDate=null, unparsed=0：静默落一条没有清仓日期的档案，用户零提示。
        String content = String.join("\n",
                String.join("\t", "代码", "名称", "介入日期", "清仓日期", "持仓天数", "买卖次数", "持仓期涨幅%"),
                "600519\t贵州茅台\t20260101");

        TradingImportParser.SoldParse p = TradingImportParser.parseSoldWithReport(content);

        assertTrue(p.headerMatched(), "表头本身是清仓股导出（不得退化成「选错文件」）");
        assertTrue(p.trades().isEmpty(),
                "清仓日期列取不到的行不得收为 SoldTrade（否则就是静默落半条档案）：" + p.trades());
        assertTrue(TradingImportParser.parseSold(content).isEmpty(),
                "薄包装 parseSold 语义不变（仍是 trades 的子集）——但也不可能再给出 sellDate=null 的行");
        assertEquals(1, p.unparsedRows().size(), "丢行必须如实上报（原来 unparsed=0）");
        String dropped = p.unparsedRows().get(0);
        assertTrue(dropped.startsWith("第 2 行"), "行号 = 原文件行号（1 起算，含表头行）：" + dropped);
        assertTrue(dropped.contains("600519") && dropped.contains("贵州茅台"),
                "原文要带上，用户能对上文件：" + dropped);
        assertTrue(dropped.contains("清仓日期"), "原因要指名是哪一列缺：" + dropped);
        assertTrue(dropped.contains("取不到"), "列被截断要说清是「列取不到」：" + dropped);
    }

    @Test
    void parseSoldWithReport_coreDateColumnsEmptyOrUnparsable_reportedRowByRow() {
        // 判据三级（列取不到 / 为空 / 不是 yyyyMMdd）逐行验证；正常行（第 2 行）不受影响。
        // 依据：清仓股导出 = 已了结交易，A 股 T+1 ⇒ 介入与清仓日期必然都有值；
        // parseDateSafe 对空串与垃圾值同样返回 null（都读不到），故二者一律丢行。
        String content = String.join("\n",
                String.join("\t", "代码", "名称", "介入日期", "清仓日期", "持仓天数", "买卖次数", "持仓期涨幅%"),
                String.join("\t", "600206", "有研新材", "20260731", "20260803", "3", "1+1", "-12.82"),
                "600519\t贵州茅台\t20260101",
                "600584\t长电科技\t\t20260803\t12\t5+1\t-29.22",
                "600585\t海螺水泥\t20260722\t\t12\t5+1\t-29.22",
                "600586\t金晶科技\t2026-07-22\t20260803\t12\t5+1\t-29.22",
                "600587\t祁连山\t20260722\t2026/08/03\t12\t5+1\t-29.22");

        TradingImportParser.SoldParse p = TradingImportParser.parseSoldWithReport(content);

        assertEquals(1, p.trades().size(), "正常行照常解析");
        assertEquals("600206", p.trades().get(0).symbol());
        assertEquals(5, p.unparsedRows().size(), "5 行核心日期读不到 → 全部如实上报（原来全静默）");
        // 每行行号 = 原文件行号（1 起算，含表头）
        for (int i = 0; i < 5; i++) {
            assertTrue(p.unparsedRows().get(i).startsWith("第 " + (i + 3) + " 行"),
                    "行号要能对上文件：" + p.unparsedRows().get(i));
        }
        assertTrue(p.unparsedRows().get(0).contains("清仓日期") && p.unparsedRows().get(0).contains("取不到"),
                p.unparsedRows().get(0));
        assertTrue(p.unparsedRows().get(1).contains("介入日期") && p.unparsedRows().get(1).contains("为空"),
                p.unparsedRows().get(1));
        assertTrue(p.unparsedRows().get(2).contains("清仓日期") && p.unparsedRows().get(2).contains("为空"),
                p.unparsedRows().get(2));
        assertTrue(p.unparsedRows().get(3).contains("介入日期") && p.unparsedRows().get(3).contains("yyyyMMdd"),
                p.unparsedRows().get(3));
        assertTrue(p.unparsedRows().get(4).contains("清仓日期") && p.unparsedRows().get(4).contains("yyyyMMdd"),
                p.unparsedRows().get(4));
        // 被丢的行绝不能以 null 日期形态出现在结果里
        assertTrue(p.trades().stream().allMatch(t -> t.buyDate() != null && t.sellDate() != null),
                "收进来的行必须两个日期都在");
    }

    @Test
    void parseSold_wrapperStaysBackwardCompatible() {
        // parseSold 是薄包装：结果必须与带报告版完全一致（既有调用点零影响）
        String content = """
                代码\t名称\t涨幅%\t现价\t介入日期\t清仓日期\t持仓天数\t买卖次数\t持仓期涨幅%\t清仓天数\t清仓后涨幅%
                600206\t有研新材\t1.14\t50.78\t20260731\t20260803\t3\t1+1\t-12.82\t11\t53.32
                600584\t长电科技\t1.14\t78.71\t20260722\t20260803\t12\t5+1\t-29.22\t11\t29.20
                #数据来源:通达信
                """;
        TradingImportParser.SoldParse reported = TradingImportParser.parseSoldWithReport(content);
        assertEquals(2, reported.trades().size());
        assertTrue(reported.unparsedRows().isEmpty(), "正常清仓股文件不得报出丢行：" + reported.unparsedRows());
        assertEquals(TradingImportParser.parseSold(content), reported.trades());
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
        assertTrue(TradingImportParser.parseSoldWithReport(content).headerMatched(),
                "真正的清仓股表头 → headerMatched=true（不能被 fail-closed 误伤）");
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
        // P2-交易83（2026-10-04）：不只给原文——带**行号 + 原因**，用户能对上文件里是哪一行
        String dropped = q.unparsedRows().get(0);
        assertTrue(dropped.startsWith("第 4 行"), "行号 = 原文件行号（1 起算）：" + dropped);
        assertTrue(dropped.contains("这不是明细行"), "原文要带上：" + dropped);
        assertTrue(dropped.contains("6 位数字"), "原因要写清：" + dropped);
    }

    @Test
    void parseCash_normalRows_produceNoUnparsedEntries() {
        // 防误伤：正常明细（含 no-6 位代码的股东代码等其他列）不得被算成丢行
        String content = "人民币: 余额:292.88  可用:292.88  可取:292.88  参考市值:110212.00  资产:110504.88  盈亏:15235.55\n"
                + "编号 证券代码 证券名称 证券数量 成本价 当前价 浮动盈亏\n"
                + "1 600809 山西汾酒 100.00 122.3849 123.5200 113.44 A000000000\n"
                + "2 000725 京东方Ａ 5300.00 6.0421 5.8100 -1229.57 A000000000\n";
        TradingImportParser.CashQuery q = TradingImportParser.parseCash(content);
        assertEquals(2, q.positions().size());
        assertTrue(q.unparsedRows().isEmpty(), "正常文件不得报出丢行：" + q.unparsedRows());
    }

    @Test
    void parseCash_structuralLines_notCountedAsUnparsed() {
        // P3 补修：资金链原先只排除了空行 / 行首 `#` / `-` 开头，`"   "`、`"====="`、
        // 前导空格的注释都会被误报成「某只持仓的精确成本没更新」——与自选/清仓链同口径排除。
        String content = String.join("\n",
                "人民币: 余额:292.88  可用:292.88  可取:292.88  参考市值:110212.00  资产:110504.88  盈亏:15235.55",
                "---------------------------------------",
                "编号 证券代码 证券名称 证券数量 成本价 当前价 浮动盈亏",
                "1 600809 山西汾酒 100.00 122.3849 123.5200 113.44",
                "   ",
                "=====",
                "  #数据来源:通达信");

        TradingImportParser.CashQuery q = TradingImportParser.parseCash(content);

        assertEquals(1, q.positions().size(), "正常明细照常解析");
        assertTrue(q.unparsedRows().isEmpty(),
                "结构性行（空白行 / 分隔线 / 缩进注释）不算丢行：" + q.unparsedRows());
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

    // ── 2026-10-06 ingest 批：统一入口（识别 / 排序 / 持仓解析 / 文件名日期 / 主板判据）──

    @Test
    void importKind_order_snapshotsBeforeTrades() {
        // 统一入口内部排序（用户 2026-10-05 拍板「快照在前、流水在后」，偏离设计 §3① 字面）：
        // 资金(10) → 持仓(20) → 成交(30) → 清仓(40) → 自选(50) → 未知(90)——order 必须严格递增。
        // 快照先落有硬依赖：① 持仓导入的「当日盈亏」写入要求账户快照已存在；
        // ② 锚定日推进后本批流水 ≤ 锚定日一律走补录（不重放、批内不双计）。
        TradingImportParser.ImportKind[] chain = {
                TradingImportParser.ImportKind.CASH,
                TradingImportParser.ImportKind.POSITIONS,
                TradingImportParser.ImportKind.TRADES,
                TradingImportParser.ImportKind.SOLD,
                TradingImportParser.ImportKind.WATCHLIST,
                TradingImportParser.ImportKind.UNKNOWN};
        for (int i = 1; i < chain.length; i++) {
            assertTrue(chain[i - 1].order() < chain[i].order(),
                    chain[i - 1] + "(" + chain[i - 1].order() + ") 必须先于 " + chain[i]
                            + "(" + chain[i].order() + ") 处理");
        }
        assertEquals("资金股份", TradingImportParser.ImportKind.CASH.label());
        assertEquals("持仓股", TradingImportParser.ImportKind.POSITIONS.label());
        assertEquals("历史成交", TradingImportParser.ImportKind.TRADES.label());
        assertEquals("清仓股", TradingImportParser.ImportKind.SOLD.label());
        assertEquals("自选股", TradingImportParser.ImportKind.WATCHLIST.label());
        assertEquals("无法识别", TradingImportParser.ImportKind.UNKNOWN.label());
    }

    @Test
    void detectKind_recognizesAllFiveKinds() {
        // 五类真实导出表头形状——判据与各链既有门槛逐条一致（不认文件名：文件名用户可改）
        String cash = "人民币: 余额:292.88  可用:292.88  可取:292.88  参考市值:110212.00  资产:110504.88  盈亏:15235.55\n"
                + "编号 证券代码 证券名称 证券数量 成本价 当前价 浮动盈亏\n"
                + "1 600206 有研新材 900 46.012 50.78 100.0\n";
        String trades = "成交日期\t证券代码\t买卖标志\t成交数量\t成交价格\t成交编号\n"
                + "20261006\t600206\t买入\t100\t50.00\t1\n";
        String sold = "代码\t名称\t涨幅%\t现价\t介入日期\t清仓日期\t持仓天数\t买卖次数\t持仓期涨幅%\n"
                + "600206\t有研新材\t6.60\t53.65\t20260731\t20260826\t26\t6+2\t32.45\n";
        String positions = "证券代码\t证券名称\t证券数量\t成本价\t当日盈亏\n"
                + "600206\t有研新材\t900\t46.012\t100.00\n";
        String watchlist = "代码\t名称\t细分行业\t一二级行业\t长期形态\t中期形态\t短期形态\t近日指标提示\n"
                + "000725\t京东方Ａ\t元器件\t信息产业-元器件\t6\t8\t1\tKDJ死叉\n";

        assertEquals(TradingImportParser.ImportKind.CASH, TradingImportParser.detectKind(cash));
        assertEquals(TradingImportParser.ImportKind.TRADES, TradingImportParser.detectKind(trades));
        assertEquals(TradingImportParser.ImportKind.SOLD, TradingImportParser.detectKind(sold));
        assertEquals(TradingImportParser.ImportKind.POSITIONS, TradingImportParser.detectKind(positions));
        assertEquals(TradingImportParser.ImportKind.WATCHLIST, TradingImportParser.detectKind(watchlist));
    }

    @Test
    void detectKind_cashDetailHeaderNotStolenByPositions() {
        // 资金明细表头（若首行丢了/被截断）不得被认成持仓股——持仓判据必须排除「当前价/浮动盈亏」形状。
        // 排除理由：资金明细也有 证券代码/证券数量/成本价 列，不排除会把资金股份文件抢过来当持仓解析。
        String cashDetailOnly = "编号\t证券代码\t证券名称\t证券数量\t成本价\t当前价\t浮动盈亏\n"
                + "1\t600206\t有研新材\t900\t46.012\t50.78\t100.0\n";
        assertEquals(TradingImportParser.ImportKind.UNKNOWN, TradingImportParser.detectKind(cashDetailOnly),
                "含「当前价」列 = 资金明细形状，不能被抢成持仓股");
        // 对照：同一形状去掉「当前价」后就应按持仓识别（证明排除项正是这两列）
        String asPositions = "编号\t证券代码\t证券名称\t证券数量\t成本价\t当日盈亏\n"
                + "1\t600206\t有研新材\t900\t46.012\t100.0\n";
        assertEquals(TradingImportParser.ImportKind.POSITIONS, TradingImportParser.detectKind(asPositions));
    }

    @Test
    void detectKind_unknownForGarbageOrEmpty() {
        // 认不出 → 如实 UNKNOWN（不猜、不静默入库）；空文件与乱文都不例外
        assertEquals(TradingImportParser.ImportKind.UNKNOWN, TradingImportParser.detectKind(""));
        assertEquals(TradingImportParser.ImportKind.UNKNOWN, TradingImportParser.detectKind(null));
        String bankFlow = "交易日期\t摘要\t发生额\t余额\n"
                + "20261006\t转账\t500.00\t3000.00\n";
        assertEquals(TradingImportParser.ImportKind.UNKNOWN, TradingImportParser.detectKind(bankFlow),
                "银行流水（资金流水）暂无解析器——登记待样本，如实拒绝");
    }

    @Test
    void parsePositions_realExport_negativeCostAllowedZeroQtySkippedTodayPnlSummed() {
        // 真实形态（2026-09-13 负成本批 + 0 股残留）：
        // ① 成本价 -5.078 合法（反复做 T/分红摊到 0 以下，实测 600601 方正科技）；
        // ② 0 股行进 skipped（看懂但不是持仓），其「当日盈亏」仍计入总额（当日清仓的已实现盈亏）。
        String content = "证券代码\t证券名称\t证券数量\t成本价\t现价\t当日盈亏\n"
                + "600601\t方正科技\t3000\t-5.078\t7.51\t408.00\n"
                + "000725\t京东方Ａ\t0\t6.0421\t5.81\t-122.57\n"
                + "600206\t有研新材\t900\t46.012\t50.78\t100.00\n";
        TradingImportParser.PositionParse p = TradingImportParser.parsePositions(content);

        assertTrue(p.headerMatched());
        assertTrue(p.unparsedRows().isEmpty(), "正常文件不得报丢行：" + p.unparsedRows());
        assertEquals(2, p.rows().size(), "0 股行不算持仓");
        TradingImportParser.PositionRow first = p.rows().get(0);
        assertEquals("600601", first.symbol());
        assertEquals(3000, first.quantity());
        assertEquals(0, new BigDecimal("-5.078").compareTo(first.avgCost()), "负数成本合法，不得拒");
        assertEquals(0, new BigDecimal("7.51").compareTo(first.currentPrice()));
        assertEquals(1, p.skipped().size(), "0 股残留进 skipped（看懂但不是持仓）");
        assertTrue(p.skipped().get(0).contains("0 股"), p.skipped().get(0));
        assertEquals(0, new BigDecimal("385.43").compareTo(p.todayPnl()),
                "当日盈亏 = 408.00 - 122.57 + 100.00（含 0 股行的当日已实现）");
    }

    @Test
    void parsePositions_badRows_reportedNotSilentlyDropped() {
        // 持仓导入是全量覆盖：丢一行 = 静默删一只持仓 → 每一行没看懂的都必须带行号+原文+原因上报。
        String content = "证券代码\t证券名称\t证券数量\t成本价\t当日盈亏\n"
                + "600000\t浦发银行\t1000\t10.5\t1.00\n"
                + "600001\t不足列\n"
                + "6004\t截断代码\t100\t1.0\t0\n"
                + "600002\t坏数量\t12.5\t1.0\t0\n"
                + "600003\t坏成本\t100\tabc\t0\n";
        TradingImportParser.PositionParse p = TradingImportParser.parsePositions(content);

        assertEquals(1, p.rows().size(), "只有第 2 行是有效持仓");
        assertEquals(4, p.unparsedRows().size(), "4 行没看懂必须逐行上报（原来会静默丢）");
        assertTrue(p.unparsedRows().get(0).startsWith("第 3 行"), p.unparsedRows().get(0));
        assertTrue(p.unparsedRows().get(0).contains("字段不足"), p.unparsedRows().get(0));
        assertTrue(p.unparsedRows().get(1).contains("6 位数字"), p.unparsedRows().get(1));
        assertTrue(p.unparsedRows().get(2).contains("不是整数"), p.unparsedRows().get(2));
        assertTrue(p.unparsedRows().get(3).contains("不是数字"), p.unparsedRows().get(3));
    }

    @Test
    void parsePositions_wrongFileOrEmpty_headerNotMatched() {
        // 选错文件（资金明细表头）与空文件：headerMatched=false，调用方 fail-closed（绝不静默 0 只）
        String cashDetail = "编号\t证券代码\t证券名称\t证券数量\t成本价\t当前价\t浮动盈亏\n"
                + "1\t600206\t有研新材\t900\t46.012\t50.78\t100.0\n";
        assertFalse(TradingImportParser.parsePositions(cashDetail).headerMatched(),
                "含「当前价」的资金明细不能被当作持仓解析");
        assertTrue(TradingImportParser.parsePositions(cashDetail).rows().isEmpty());
        assertFalse(TradingImportParser.parsePositions("").headerMatched());
    }

    @Test
    void parsePositions_currentPriceOnlyWhenPositive() {
        // 现价必须 > 0 才有意义（成本价允许为负，现价不允许）——取不到/非正 → null
        // （导入侧保留原有存储价，绝不写回 0 或成本价——P2-交易65 同源）
        String content = "证券代码\t证券名称\t证券数量\t成本价\t现价\t当日盈亏\n"
                + "600000\t浦发银行\t1000\t10.5\t0.00\t1.00\n"
                + "600001\t示例股\t100\t1.0\t--\t0\n";
        TradingImportParser.PositionParse p = TradingImportParser.parsePositions(content);

        assertEquals(2, p.rows().size());
        assertNull(p.rows().get(0).currentPrice(), "现价 0.00 → null，不得带上送");
        assertNull(p.rows().get(1).currentPrice(), "现价不是数字 → null");
    }

    @Test
    void parseDateFromFilename_realNamingAndFakeDateSkipped() {
        // 通达信文件名惯例：前导时间戳 + 主题 + 日期；假日期（20260230）跳过继续找，宁可无据不猜
        assertEquals(LocalDate.of(2026, 9, 4),
                TradingImportParser.parseDateFromFilename("20260904004455_持仓股20260904.txt"));
        assertEquals(LocalDate.of(2026, 9, 4),
                TradingImportParser.parseDateFromFilename("2026-09-04_资金股份.txt"));
        assertNull(TradingImportParser.parseDateFromFilename("20260230_持仓股.txt"),
                "日历上不存在的日期 → null（无据）");
        assertEquals(LocalDate.of(2026, 9, 4),
                TradingImportParser.parseDateFromFilename("20260230_20260904_持仓股.txt"),
                "假日期跳过继续找后面的真日期");
        assertNull(TradingImportParser.parseDateFromFilename("持仓股.txt"));
        assertNull(TradingImportParser.parseDateFromFilename(null));
    }

    @Test
    void isMainboardCode_onlyBoardSegments() {
        // 设计 §11.2「限制只在账」：600/601/603/605 · 000/001/002/003 才入账；
        // 科创/创业/北交所/ETF/可转债/港美股如实拒绝（不静默）
        assertTrue(TradingImportParser.isMainboardCode("600519"));
        assertTrue(TradingImportParser.isMainboardCode("601066"));
        assertTrue(TradingImportParser.isMainboardCode("603206"));
        assertTrue(TradingImportParser.isMainboardCode("605499"));
        assertTrue(TradingImportParser.isMainboardCode("000725"));
        assertTrue(TradingImportParser.isMainboardCode("001979"));
        assertTrue(TradingImportParser.isMainboardCode("002428"));
        assertTrue(TradingImportParser.isMainboardCode("003816"));
        assertFalse(TradingImportParser.isMainboardCode("688981"), "科创板不入账");
        assertFalse(TradingImportParser.isMainboardCode("300750"), "创业板不入账");
        assertFalse(TradingImportParser.isMainboardCode("301111"), "创业板不入账");
        assertFalse(TradingImportParser.isMainboardCode("830799"), "北交所不入账");
        assertFalse(TradingImportParser.isMainboardCode("430047"), "北交所不入账");
        assertFalse(TradingImportParser.isMainboardCode("512690"), "ETF 不入账");
        assertFalse(TradingImportParser.isMainboardCode("113050"), "可转债不入账");
        assertFalse(TradingImportParser.isMainboardCode("007001"));
        assertFalse(TradingImportParser.isMainboardCode("12345"));
        assertFalse(TradingImportParser.isMainboardCode(null));
        assertFalse(TradingImportParser.isMainboardCode(""));
    }
}

