package com.adaiadai.core.infrastructure.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TdxDataPackageImporter 单元测试（MD17 行情数据包导入）。
 * 用真实 zip 字节构造：sh/sz .day 合法二进制 + 嵌套路径 + 坏文件 + 非 .day。
 */
class TdxDataPackageImporterTest {

    @TempDir
    Path tdxDir;

    private TdxDataPackageImporter importer() {
        return new TdxDataPackageImporter(tdxDir.toString());
    }

    /** 构造合法 .day 二进制：n 条 32 字节小端记录（date 递增）。 */
    private byte[] dayBytes(int startDate, int n) {
        ByteBuffer buf = ByteBuffer.allocate(n * 32).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            int date = startDate + i; // 20260901 + i：跨日进位不合法但 parse 单条失败即跳过——保持同日
            buf.putInt(date).putInt(500 * 100).putInt(510 * 100).putInt(490 * 100)
                    .putInt(505 * 100).putInt(1_000_000).putInt(100_000).putInt(0);
        }
        return buf.array();
    }

    /** 把条目打包成 zip 字节。 */
    private byte[] zipOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    @Test
    void importZip_shAndSzWithNestedPath_andSkipUnsupported() throws Exception {
        // vipdoc 嵌套路径 + 根下直接 .day 混装（兼容 sync_tdx_data.sh 的两种布局）
        byte[] zip = zipOf(Map.of(
                "vipdoc/sh/lday/sh600519.day", dayBytes(20260901, 3),
                "sz/lday/sz000001.day", dayBytes(20260901, 5),
                "readme.txt", "注意：盘后数据".getBytes(),
                "hk00700.day", dayBytes(20260901, 2))); // 非 sh/sz 前缀 → 跳过

        TdxDataPackageImporter.ImportResult r = importer().importZip(zip, "sh_sz.zip");

        assertEquals(2, r.imported());
        assertEquals(2, r.skipped()); // readme.txt + hk00700.day
        assertEquals(0, r.failedCount());
        assertEquals(2, r.dayFilesAfter());
        assertEquals(Map.of("sh", 1, "sz", 1), r.markets());

        // 落盘位置：按市场分流重建目录（防 zip-slip，不沿嵌套路径解压）
        Path sh = tdxDir.resolve("sh/lday/sh600519.day");
        Path sz = tdxDir.resolve("sz/lday/sz000001.day");
        assertTrue(Files.isRegularFile(sh));
        assertTrue(Files.isRegularFile(sz));
        assertTrue(Arrays.equals(dayBytes(20260901, 3), Files.readAllBytes(sh)));
        // 不应有 vipdoc 嵌套目录
        assertFalse(Files.exists(tdxDir.resolve("vipdoc")));
    }

    @Test
    void importZip_badDayFile_reportedNotBlocking() throws Exception {
        byte[] zip = zipOf(Map.of(
                "sh/lday/sh600519.day", dayBytes(20260901, 3),
                "sz/lday/sz000002.day", "not-a-day-binary".getBytes())); // 非 32 倍数 → 无法解析

        TdxDataPackageImporter.ImportResult r = importer().importZip(zip, "mixed.zip");

        assertEquals(1, r.imported());
        assertEquals(1, r.failedCount());
        assertTrue(r.failed().get(0).contains("sz000002.day"));
        assertTrue(Files.isRegularFile(tdxDir.resolve("sh/lday/sh600519.day")));
        assertFalse(Files.exists(tdxDir.resolve("sz/lday/sz000002.day")));
    }

    @Test
    void importZip_emptyZip_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> importer().importZip(new byte[0], "empty.zip"));
    }

    @Test
    void importZip_noDayFiles_throws() {
        byte[] zip = zipOf(Map.of("notes.txt", "nothing".getBytes()));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> importer().importZip(zip, "no-day.zip"));
        assertTrue(ex.getMessage().contains("没有任何 .day"));
    }

    @Test
    void importZip_allDayBroken_throws() {
        byte[] zip = zipOf(Map.of(
                "sh/lday/sh600519.day", "garbage".getBytes(),
                "sz/lday/sz000002.day", "also-broken".getBytes()));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> importer().importZip(zip, "broken.zip"));
        assertTrue(ex.getMessage().contains("全部无法解析"));
    }

    @Test
    void importZip_notZip_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> importer().importZip("plain text, not zip".getBytes(), "fake.zip"));
    }

    @Test
    void importZip_overwriteExisting_replacesContent() throws Exception {
        Path target = tdxDir.resolve("sh/lday/sh600519.day");
        byte[] v1 = dayBytes(20260901, 2);
        byte[] v2 = dayBytes(20260902, 4);
        importer().importZip(zipOf(Map.of("sh/lday/sh600519.day", v1)), "v1.zip");
        assertTrue(Arrays.equals(v1, Files.readAllBytes(target)));

        TdxDataPackageImporter.ImportResult r =
                importer().importZip(zipOf(Map.of("sh/lday/sh600519.day", v2)), "v2.zip");
        assertEquals(1, r.imported());
        assertTrue(Arrays.equals(v2, Files.readAllBytes(target)));
        // 无 .tmp 残留
        assertFalse(Files.exists(tdxDir.resolve("sh/lday/sh600519.day.tmp")));
    }
}
