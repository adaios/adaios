package com.adaiadai.core.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * MarketSnapshotRepository — 行情异动去重快照（Layer 5 主动推送）。
 * <p>
 * 记录每个用户「当日已推送」的异动签名（{@code symbol:date:type}），
 * 避免同一异动在轮询中反复推送刷屏。文件 {@code data/{userId}/trading/market_snapshot.json}：
 * <pre>
 * {"date":"2026-08-06","signatures":["600519:2026-08-06:loss","600123:2026-08-06:break-cost"]}
 * </pre>
 * 快照带日期：跨日后签名自然失效（返回空集），无需手动清理。
 */
@Repository
public class MarketSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(MarketSnapshotRepository.class);
    private static final String SNAPSHOT_PATH = "trading/market_snapshot.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileStorage fileStorage;

    public MarketSnapshotRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 当日已推送的异动签名集合；快照日期非当日视为空（隔日重置）。异常/损坏时返回空集（保守不误推）。
     */
    public Set<String> alertedSignatures(String userId, LocalDate date) {
        String content = fileStorage.read(userId, SNAPSHOT_PATH);
        if (content == null || content.isBlank()) return Set.of();
        try {
            var json = MAPPER.readTree(content);
            if (!date.toString().equals(json.path("date").asText())) return Set.of();
            Set<String> set = new HashSet<>();
            json.path("signatures").forEach(n -> set.add(n.asText()));
            return set;
        } catch (Exception e) {
            log.warn("读取行情快照失败 | userId={} | {}", userId, e.getMessage());
            return Set.of();
        }
    }

    /** 保存当日签名集合（全量覆盖，调用方传入「原签名 ∪ 新签名」）。 */
    public void saveSignatures(String userId, LocalDate date, Set<String> signatures) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", date.toString());
            map.put("signatures", new ArrayList<>(signatures));
            fileStorage.write(userId, SNAPSHOT_PATH, MAPPER.writeValueAsString(map));
        } catch (Exception e) {
            log.warn("保存行情快照失败 | userId={} | {}", userId, e.getMessage());
        }
    }
}
