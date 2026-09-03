package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingMarketStage;
import com.adaiadai.core.domain.trading.TradingMarketStagePort;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * TradingMarketStageRepository — 活跃市值区间持久化（v3.41，2026-09-04）。
 * <p>
 * 文件 {@code data/{userId}/trading/market-stage.json}（File First，用户私有）：
 * <pre>
 * {
 *   "stage": "bear",                  // bull（多头区间）| bear（空头区间），两档
 *   "updatedAt": "2026-09-04T09:00:00"
 * }
 * </pre>
 * 无文件/损坏/非法值 → {@code null}（表示用户从未手动判定，调用方回退 current.md 规则推断，
 * 不阻断推送——降级不坏）。写入为原子（FileStorage 覆盖语义）+ per-user 条带锁
 * （P2-交易28 锁池模式，防并发 PUT 读-改-写丢更新，pitfalls「save 无锁」复发信号）。
 */
@Repository
public class TradingMarketStageRepository implements TradingMarketStagePort {

    private static final Logger log = LoggerFactory.getLogger(TradingMarketStageRepository.class);
    private static final String STAGE_PATH = "trading/market-stage.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** per-user 条带锁（固定 16 条带，防任意 userId 撑爆 map）。 */
    private final Object[] lockStripes = new Object[16];

    private final FileStorage fileStorage;

    public TradingMarketStageRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        for (int i = 0; i < lockStripes.length; i++) lockStripes[i] = new Object();
    }

    /** userId → 条带锁（固定条带）。 */
    private Object lockFor(String userId) {
        return lockStripes[(userId != null ? userId.hashCode() : 0) & (lockStripes.length - 1)];
    }

    /** 读取用户手动判定的活跃市值区间；无文件/损坏/非法 → null（回退规则推断）。 */
    @Override
    public TradingMarketStage findByUser(String userId) {
        String content = fileStorage.read(userId, STAGE_PATH);
        if (content == null || content.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(content);
            String stage = root.path("stage").asText(null);
            String updatedAt = root.path("updatedAt").asText(null);
            if (stage == null || !TradingMarketStage.isValid(stage) || updatedAt == null) return null;
            return new TradingMarketStage(stage, updatedAt);
        } catch (Exception e) {
            log.warn("读取活跃市值区间失败（视为未判定）| userId={} | {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 保存用户手动判定的活跃市值区间。
     * <p>
     * P0-1（2026-08-30 审查模式）：写盘失败**必须抛错**——否则 Controller 返回
     * updated=true 而文件未写，用户以为生效实际丢失（信任炸弹），由调用方转 500。
     *
     * @throws com.adaiadai.core.kernel.storage.StorageException 写盘失败
     */
    @Override
    public void save(String userId, TradingMarketStage stage) {
        synchronized (lockFor(userId)) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("stage", stage.stage());
            node.put("updatedAt", stage.updatedAt());
            try {
                fileStorage.write(userId, STAGE_PATH, MAPPER.writeValueAsString(node));
            } catch (Exception e) {
                throw new StorageException(
                        "保存活跃市值区间失败 | userId=" + userId + " | " + e.getMessage(), e);
            }
        }
    }

    /** 当前时间戳（ISO 本地时间，秒精度——前端「更新于 HH:mm」用）。 */
    public static String now() {
        return LocalDateTime.now().format(TS);
    }
}
