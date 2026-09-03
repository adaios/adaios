package com.adaiadai.core.infrastructure.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * TdxDataPackageImporter — 通达信日线数据包（.zip）导入（MD17，2026-09-04 登记）。
 * <p>
 * 替代「Windows 通达信盘后数据 → 打包 → scp → 服务器手工解压」的运维流程：
 * admin 控制台上传 zip，本服务校验包结构 + 每个 .day 可解析，校验通过后
 * 按文件名前缀分流落盘到 TDX 目录（{@code sh*.day → {root}/sh/lday/}、
 * {@code sz*.day → {root}/sz/lday/}），与 {@code scripts/sync_tdx_data.sh} 分流规则一致。
 * <p>
 * 安全与原子性：
 * <ul>
 *   <li>只按条目 basename 收集 {@code (sh|sz)\d{6}.day} 文件——天然防 zip-slip
 *       （不解压到目标目录的嵌套路径，目录结构由本服务重建）</li>
 *   <li>zip bomb 防护：单条 .day 上限 {@value #MAX_ENTRY_BYTES}（日线全历史远小于此）、
 *       条目总数上限 {@value #MAX_FILE_COUNT}、解压总量由条目上限 × 单条上限封顶</li>
 *   <li>逐文件校验可解析（{@link TdxFileKlineSource#parse}），坏文件列入 failed 不落盘
 *       （缺该股时读取端自动回落网络源，不阻断其余文件）</li>
 *   <li>落盘 = 写 {@code .tmp} + 同目录 {@code ATOMIC_MOVE} 覆盖——读取方（mtime 缓存）
 *       永远看到旧文件或新文件的完整态，无半写窗口</li>
 * </ul>
 * 导入后读取端缓存按 mtime 自动失效，无需重启。
 */
@Component
public class TdxDataPackageImporter {

    private static final Logger log = LoggerFactory.getLogger(TdxDataPackageImporter.class);

    /** 单条 .day 上限（32 字节/条，40 年约 4.7MB；此值仅为 zip-bomb 护栏）。 */
    private static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;
    /** 单包 .day 文件数上限（全 A ≈ 5500/市场，双市场 + 历史冗余留余量）。 */
    private static final int MAX_FILE_COUNT = 30_000;

    /** 合法通达信日线文件名：sh/sz + 6 位代码 + .day。 */
    private static final Pattern DAY_FILE = Pattern.compile("^(sh|sz)\\d{6}\\.day$");

    private final Path root;

    public TdxDataPackageImporter(
            @Value("${adai.market.tdx-path:../../data/market/tdx}") String tdxPath) {
        this.root = Paths.get(tdxPath);
        log.info("TdxDataPackageImporter 初始化 | root={}", root.toAbsolutePath());
    }

    /** 导入结果：imported 成功文件数、failed 失败清单、market 分布、skip 非 .day 条目数。 */
    public record ImportResult(int imported, int skipped, List<String> failed,
                               Map<String, Integer> markets, int dayFilesAfter) {
        public int failedCount() {
            return failed.size();
        }
    }

    /**
     * 导入 zip 数据包。
     *
     * @param zip      数据包字节
     * @param filename 原始文件名（仅日志/错误提示用）
     * @return 导入结果
     * @throws IllegalArgumentException zip 为空 / 无任何合法 .day / 超出上限（400 人话）
     */
    public ImportResult importZip(byte[] zip, String filename) {
        if (zip == null || zip.length == 0) {
            throw new IllegalArgumentException("数据包为空（未收到文件内容）");
        }

        // 第一遍：流式收集 + 校验（全部通过才落盘，避免落一半发现坏包）
        List<PendingFile> pending = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int skipped = 0;
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (!base.toLowerCase().endsWith(".day")) {
                    skipped++;
                    continue;
                }
                if (!DAY_FILE.matcher(base).matches()) {
                    log.info("跳过非 A 股日线文件（不支持前缀/长度）| {}", base);
                    skipped++;
                    continue;
                }
                if (pending.size() >= MAX_FILE_COUNT) {
                    throw new IllegalArgumentException("数据包 .day 文件数超上限（" + MAX_FILE_COUNT
                            + "），请拆分市场包后上传");
                }
                byte[] bytes = readEntry(zis, base);
                String market = base.startsWith("sh") ? "sh" : "sz";
                try {
                    // 校验可解析（TdxFileKlineSource.parse 同包静态方法：坏数据单条跳过）
                    if (TdxFileKlineSource.parse(bytes).isEmpty()) {
                        failed.add(base + "（无法解析出任何 K 线）");
                        log.warn("通达信数据包：.day 无法解析 | {}", base);
                        continue;
                    }
                } catch (Exception e) {
                    failed.add(base + "（" + e.getMessage() + "）");
                    log.warn("通达信数据包：.day 解析异常 | {} | {}", base, e.getMessage());
                    continue;
                }
                pending.add(new PendingFile(market, base, bytes));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("数据包不是有效的 zip 文件: " + filename);
        }

        if (pending.isEmpty()) {
            String reason = failed.isEmpty()
                    ? "数据包内没有任何 .day 文件（需含 sh/sz + 6 位代码的 .day）"
                    : "数据包内 .day 全部无法解析（首个: " + failed.get(0) + "）";
            throw new IllegalArgumentException(reason);
        }

        // 第二遍：落盘（.tmp + 原子 move 覆盖）
        int imported = 0;
        Map<String, Integer> markets = new LinkedHashMap<>();
        for (PendingFile f : pending) {
            try {
                writeAtomically(f);
                imported++;
                markets.merge(f.market, 1, Integer::sum);
            } catch (IOException e) {
                failed.add(f.base + "（写入失败: " + e.getMessage() + "）");
                log.error("通达信数据包：写入失败 | {} | {}", f.base, e.getMessage());
            }
        }

        int dayTotal = countDayFiles();
        log.info("通达信数据包导入完成 | file={} | 成功 {} | 失败 {} | 跳过 {} | 当前 .day {}",
                filename, imported, failed.size(), skipped, dayTotal);
        return new ImportResult(imported, skipped, failed, markets, dayTotal);
    }

    private record PendingFile(String market, String base, byte[] bytes) {}

    /** 读单条 zip 条目（上限 {@value #MAX_ENTRY_BYTES}，超限抛错拒包）。 */
    private byte[] readEntry(InputStream in, String name) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("单文件超上限: " + name);
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** 写 {@code .tmp} 后原子 rename 覆盖目标（读方 mtime 缓存只见完整文件）。 */
    private void writeAtomically(PendingFile f) throws IOException {
        Path dir = root.resolve(f.market).resolve("lday");
        Files.createDirectories(dir);
        Path target = dir.resolve(f.base);
        Path tmp = dir.resolve(f.base + ".tmp");
        try {
            Files.write(tmp, f.bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 当前 TDX 目录 .day 总数（含 sh/sz）。 */
    private int countDayFiles() {
        int total = 0;
        for (String market : new String[]{"sh", "sz"}) {
            Path dir = root.resolve(market).resolve("lday");
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                total += stream.filter(p -> p.getFileName().toString().endsWith(".day")).count();
            } catch (IOException e) {
                log.warn("统计 .day 失败 | {} | {}", dir, e.getMessage());
            }
        }
        return total;
    }
}
