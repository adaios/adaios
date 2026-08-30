package com.adaiadai.core.infrastructure.market;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TdxFileKlineSource — 通达信本地数据源（2026-08-30：行情源被风控后的稳定替代——第一优先）。
 * <p>
 * 数据：通达信安装目录 {@code vipdoc/sh/lday/sh600519.day} / {@code vipdoc/sz/lday/sz000831.day}，
 * 每文件固定 **32 字节/条** 小端 int32：{@code date(4) open(4) high(4) low(4) close(4) amount(4) volume(4) reserved(4)}。
 * 价格 ×100 存储（int/100 = 元），volume 单位**股**（÷100 = 手，对齐 {@link Candle} 契约）。
 * date 为 yyyymmdd 整数。
 * <p>
 * 优点：全 A 覆盖、任意历史日期、零网络风控；缺点：不复权（原始价，除权日跳空）+
 * 最新到「上次下载盘后数据」——配合网络源兜底（KlineService：tdx → 腾讯 → 东财）。
 * <p>
 * 路径配置 {@code adai.market.tdx-path}（默认 {@code ../../data/market/tdx}，生产 .env 用
 * {@code ADAI_TDX_PATH=/opt/adaios/data/market/tdx}）。安全约定：文件缺失/损坏 → 空列表。
 */
@Component
public class TdxFileKlineSource implements KlineSource {

    private static final Logger log = LoggerFactory.getLogger(TdxFileKlineSource.class);
    private static final int RECORD_BYTES = 32;

    private final Path root;
    /** symbol → (文件 mtime, 蜡烛列表) 缓存（日线文件一次读全量，mtime 变化才重读）。 */
    private final Map<String, CachedKline> cache = new ConcurrentHashMap<>();

    public TdxFileKlineSource(
            @Value("${adai.market.tdx-path:../../data/market/tdx}") String tdxPath) {
        this.root = Paths.get(tdxPath);
        log.info("TdxFileKlineSource 初始化 | root={} | 存在={}", root.toAbsolutePath(), Files.isDirectory(root));
    }

    @Override
    public List<Candle> kline(String symbol, int limit) {
        if (symbol == null || symbol.isBlank()) return List.of();
        List<Candle> all = readAll(symbol);
        if (all.isEmpty()) return List.of();
        int n = Math.min(Math.max(limit, 1), all.size());
        return new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    @Override
    public List<Candle> klineRange(String symbol, LocalDate from, LocalDate to) {
        if (symbol == null || symbol.isBlank() || from == null || to == null || from.isAfter(to)) {
            return List.of();
        }
        List<Candle> all = readAll(symbol);
        if (all.isEmpty()) return List.of();
        List<Candle> window = new ArrayList<>();
        for (Candle c : all) {
            if (!c.date().isBefore(from) && !c.date().isAfter(to)) window.add(c);
        }
        return window;
    }

    /** 读全量日线（缓存 + mtime 失效）；文件缺失/解析失败 → 空。 */
    private List<Candle> readAll(String symbol) {
        CachedKline cached = cache.get(symbol);
        Path file = fileFor(symbol);
        if (file == null) return List.of();
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (cached != null && cached.mtime == mtime) {
                return cached.candles;
            }
            byte[] bytes = Files.readAllBytes(file);
            List<Candle> candles = parse(bytes);
            if (!candles.isEmpty()) {
                cache.put(symbol, new CachedKline(mtime, candles));
            }
            return candles;
        } catch (IOException e) {
            return List.of();
        } catch (Exception e) {
            log.warn("通达信日线解析失败 | symbol={} | {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /** 文件路径：{root}/sh/lday/sh{symbol}.day 或 {root}/sz/lday/sz{symbol}.day。 */
    private Path fileFor(String symbol) {
        boolean sh = symbol.startsWith("6") || symbol.startsWith("9");
        return root.resolve(sh ? "sh" : "sz").resolve("lday")
                .resolve((sh ? "sh" : "sz") + symbol + ".day");
    }

    /** 解析 .day 二进制（32 字节/条小端 int32）。 */
    static List<Candle> parse(byte[] bytes) {
        List<Candle> candles = new ArrayList<>();
        int count = bytes.length / RECORD_BYTES;
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int dateInt = buf.getInt();
            int open = buf.getInt();
            int high = buf.getInt();
            int low = buf.getInt();
            int close = buf.getInt();
            buf.getInt(); // amount 成交额
            int volume = buf.getInt(); // 股
            buf.getInt(); // reserved
            if (dateInt <= 0) continue;
            try {
                LocalDate date = LocalDate.of(dateInt / 10000, (dateInt / 100) % 100, dateInt % 100);
                candles.add(new Candle(date,
                        open / 100.0, high / 100.0, low / 100.0, close / 100.0,
                        volume / 100.0));
            } catch (Exception e) {
                // 单条坏数据跳过（文件尾部半条/填充）
            }
        }
        return candles;
    }

    private record CachedKline(long mtime, List<Candle> candles) {}
}
