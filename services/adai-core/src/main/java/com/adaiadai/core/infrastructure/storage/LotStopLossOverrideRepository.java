package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LotStopLossOverrideRepository — 批次级止损覆盖持久化（2026-09-04 按批次止损批，task-log「2026-09-04 交易-批次止损」）。
 * <p>
 * 背景：批次 = 流水重放投影不落盘，非初始批次的止损锁死在买入流水里，事后改不了；
 * 持仓级止损只影响底仓批。本仓库提供**批次级止损覆盖层**（决策文档 P1 方案 A）——
 * 用户对某批次单独设/改止损（如新加仓收紧、老底仓放宽），推导后合并覆盖流水值，不污染流水真相源。
 * <p>
 * 文件 {@code data/{userId}/trading/lot-stoploss.json}（File First，用户私有）：
 * <pre>
 * { "600519_20260901_B": "1500.00", "600519_INIT": "1350.00" }
 * </pre>
 * value 为价格字符串（BigDecimal 显式 toPlainString，防 JSON 科学计数法丢精度）。
 * <p>
 * 语义：
 * <ul>
 *   <li>{@link #findByUser}：读全部覆盖（无文件/损坏 → 空 map，降级不坏——批次回退流水止损/默认 −7%）</li>
 *   <li>{@link #setStopLoss}/{@link #removeStopLoss}：RMW 原子写（per-user 条带锁 + FileStorage 覆盖语义），
 *       写失败抛 {@link StorageException}（fail-visible，防「以为设了实际没写」）</li>
 *   <li>读取不做缓存：文件极小，且即时生效优于 TTL 陈旧（改完立即在批次明细/预警中可见）</li>
 * </ul>
 */
@Repository
public class LotStopLossOverrideRepository {

    private static final Logger log = LoggerFactory.getLogger(LotStopLossOverrideRepository.class);
    private static final String OVERRIDE_PATH = "trading/lot-stoploss.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** per-user 条带锁（固定 16 条带，P2-交易28 锁池模式）。 */
    private final Object[] lockStripes = new Object[16];

    private final FileStorage fileStorage;

    public LotStopLossOverrideRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        for (int i = 0; i < lockStripes.length; i++) lockStripes[i] = new Object();
    }

    /** userId → 条带锁。 */
    private Object lockFor(String userId) {
        return lockStripes[(userId != null ? userId.hashCode() : 0) & (lockStripes.length - 1)];
    }

    /** 读取该用户全部批次止损覆盖；无文件/损坏 → 空 map（降级不坏）。 */
    public Map<String, BigDecimal> findByUser(String userId) {
        String content = fileStorage.read(userId, OVERRIDE_PATH);
        if (content == null || content.isBlank()) return Map.of();
        try {
            JsonNode root = MAPPER.readTree(content);
            if (!root.isObject()) return Map.of();
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            root.fields().forEachRemaining(e -> {
                String v = e.getValue().asText(null);
                if (v != null) {
                    try {
                        result.put(e.getKey(), new BigDecimal(v));
                    } catch (NumberFormatException ex) {
                        log.warn("批次止损覆盖含非法价格，跳过 | userId={} | lotId={}", userId, e.getKey());
                    }
                }
            });
            return result;
        } catch (Exception e) {
            log.warn("读取批次止损覆盖失败（视为无覆盖）| userId={} | {}", userId, e.getMessage());
            return Map.of();
        }
    }

    /** 设/改某批次止损覆盖（RMW 原子写）。写失败抛 StorageException（fail-visible）。 */
    public void setStopLoss(String userId, String lotId, BigDecimal price) {
        synchronized (lockFor(userId)) {
            Map<String, BigDecimal> all = new LinkedHashMap<>(findByUser(userId));
            all.put(lotId, price);
            write(userId, all);
        }
    }

    /** 清除某批次止损覆盖（回退流水止损/默认 −7%）；不存在 → 幂等成功。 */
    public void removeStopLoss(String userId, String lotId) {
        synchronized (lockFor(userId)) {
            Map<String, BigDecimal> all = new LinkedHashMap<>(findByUser(userId));
            if (all.remove(lotId) == null) return;
            write(userId, all);
        }
    }

    private void write(String userId, Map<String, BigDecimal> overrides) {
        ObjectNode node = MAPPER.createObjectNode();
        overrides.forEach((lotId, price) -> node.put(lotId, price.stripTrailingZeros().toPlainString()));
        try {
            fileStorage.write(userId, OVERRIDE_PATH, MAPPER.writeValueAsString(node));
        } catch (Exception e) {
            throw new StorageException(
                    "保存批次止损覆盖失败 | userId=" + userId + " | " + e.getMessage(), e);
        }
    }
}
