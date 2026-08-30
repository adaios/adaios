package com.adaiadai.core.infrastructure.market;

import com.adaiadai.core.domain.trading.market.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TdxFileKlineSourceTest — 通达信本地数据源（2026-08-30：行情源风控后的稳定替代）。
 * <p>
 * 验证：.day 二进制解析（32 字节/条小端 int32，价格 ×100、volume 股→手）、
 * kline 最近 N 根、klineRange 日期过滤、文件缺失空、缓存 mtime 失效。
 */
class TdxFileKlineSourceTest {

    @TempDir
    Path tempDir;

    /** 构造 .day 二进制（date yyyymmdd, open/high/low/close ×100, amount, volume 股, reserved）。 */
    private byte[] buildDay(int[][] rows) {
        ByteBuffer buf = ByteBuffer.allocate(rows.length * 32).order(ByteOrder.LITTLE_ENDIAN);
        for (int[] r : rows) {
            buf.putInt(r[0]);            // date
            buf.putInt(r[1]);            // open（×100）
            buf.putInt(r[2]);            // high（×100）
            buf.putInt(r[3]);            // low（×100）
            buf.putInt(r[4]);            // close（×100）
            buf.putInt(r[5]);            // amount
            buf.putInt(r[6]);            // volume 股
            buf.putInt(0);               // reserved
        }
        return buf.array();
    }

    private TdxFileKlineSource source(String symbol, byte[] day) throws Exception {
        boolean sh = symbol.startsWith("6");
        Path dir = tempDir.resolve(sh ? "sh" : "sz").resolve("lday");
        Files.createDirectories(dir);
        Files.write(dir.resolve((sh ? "sh" : "sz") + symbol + ".day"), day);
        return new TdxFileKlineSource(tempDir.toString());
    }

    @Test
    void parse_decodesPricesAndVolume() throws Exception {
        // 3 条：2026-08-01..03
        int[][] rows = {
                {20260801, 1000, 1050, 990, 1030, 500000, 10000},  // close 10.30, vol 10000 股 = 100 手
                {20260802, 1030, 1100, 1020, 1090, 600000, 12000},
                {20260803, 1090, 1150, 1080, 1140, 700000, 15000},
        };
        TdxFileKlineSource src = source("600519", buildDay(rows));
        List<Candle> all = src.klineRange("600519", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3));
        assertEquals(3, all.size());
        Candle c0 = all.get(0);
        assertEquals(LocalDate.of(2026, 8, 1), c0.date());
        assertEquals(10.30, c0.close(), 0.001, "价格 ×100 存储 → /100");
        assertEquals(11.50, all.get(2).high(), 0.001);
        assertEquals(100.0, c0.volume(), 0.001, "volume 股 → 手（/100）");
        assertEquals(150.0, all.get(2).volume(), 0.001);
    }

    @Test
    void kline_returnsLastN() throws Exception {
        int[][] rows = {
                {20260801, 10, 11, 9, 10, 1, 1000},
                {20260802, 10, 11, 9, 11, 1, 1000},
                {20260803, 11, 12, 10, 12, 1, 1000},
        };
        TdxFileKlineSource src = source("600519", buildDay(rows));
        List<Candle> last = src.kline("600519", 2);
        assertEquals(2, last.size());
        assertEquals(LocalDate.of(2026, 8, 2), last.get(0).date());
        assertEquals(LocalDate.of(2026, 8, 3), last.get(1).date());
    }

    @Test
    void klineRange_filtersByDate() throws Exception {
        int[][] rows = {
                {20260729, 10, 11, 9, 10, 1, 1000},
                {20260730, 10, 11, 9, 11, 1, 1000},
                {20260803, 11, 12, 10, 12, 1, 1000},
        };
        TdxFileKlineSource src = source("600519", buildDay(rows));
        List<Candle> window = src.klineRange("600519", LocalDate.of(2026, 7, 30), LocalDate.of(2026, 8, 3));
        assertEquals(2, window.size());
        assertEquals(LocalDate.of(2026, 7, 30), window.get(0).date());
    }

    @Test
    void missingFile_returnsEmpty() throws Exception {
        TdxFileKlineSource src = new TdxFileKlineSource(tempDir.toString());
        assertTrue(src.kline("000001", 10).isEmpty(), "文件缺失 → 空（不抛错，安全约定）");
    }

    @Test
    void cacheInvalidatedByMtime() throws Exception {
        int[][] rows = {{20260801, 10, 11, 9, 10, 1, 1000}};
        TdxFileKlineSource src = source("600519", buildDay(rows));
        assertEquals(1, src.kline("600519", 10).size(), "首次读取");

        // 追加一条并确保 mtime 变化（等 10ms）
        int[][] rows2 = {{20260801, 10, 11, 9, 10, 1, 1000}, {20260802, 10, 11, 9, 11, 1, 1000}};
        Path f = tempDir.resolve("sh/lday/sh600519.day");
        Thread.sleep(15);
        Files.write(f, buildDay(rows2));
        assertEquals(2, src.kline("600519", 10).size(), "mtime 变化 → 缓存失效重读");
    }

    @Test
    void corruptedBytes_skipsBadRecords() throws Exception {
        byte[] good = buildDay(new int[][]{{20260801, 10, 11, 9, 10, 1, 1000}});
        byte[] corrupted = new byte[good.length + 10]; // 尾部半条
        System.arraycopy(good, 0, corrupted, 0, good.length);
        TdxFileKlineSource src = source("600519", corrupted);
        assertEquals(1, src.kline("600519", 10).size(), "半条尾部应跳过");
    }
}
