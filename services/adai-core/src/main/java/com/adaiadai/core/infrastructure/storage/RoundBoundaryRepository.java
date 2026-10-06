package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.RoundBoundary;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * RoundBoundaryRepository — 「笔的边界」人工覆盖持久化（design-final §2.3/§7，2026-10-06）。
 *
 * <p>背景：笔的切分是**派生量**（从流水实时算、不落盘）；唯一需要落盘的是**人工介入**——
 * 用户点「从这笔买入开始算新的一笔」（cut）或「与上一笔合并」（merge），以及给某笔的备注。
 * 自动边界不落盘（每次重算，天然「改完即重算」）；本仓库只存人工侧，保证
 * **可追溯 / 可撤销 / 自动让位（人工 &gt; 自动）**。
 *
 * <p>文件 {@code data/{userId}/trading/round-boundaries.json}（File First，用户私有）：
 * <pre>
 * { "boundaries": [
 *   {"symbol":"600206","anchorBuyId":"t_600206_2026-08-05","anchorDate":"2026-08-05",
 *    "mode":"cut","note":"这里开始第二段","updatedAt":"2026-10-06T18:00:00"}
 * ]}
 * </pre>
 *
 * <p>语义：
 * <ul>
 *   <li>{@link #findByUser}：读全部人工边界（按操作顺序，读到末尾=最后操作生效）；
 *       无文件 → 空 list；损坏 → **先备份 .bak-corrupt** 再降级空（降级不坏——回纯自动口径；
 *       备份防「损坏 + 写侧全量重写」丢残余，P2-交易95）</li>
 *   <li>{@link #upsert}：同 (symbol, anchorBuyId) 覆盖（后写为准）+ 移到末尾</li>
 *   <li>{@link #remove}：清除某锚点（回到自动让位）；不存在 → 幂等成功</li>
 *   <li>写失败抛 {@link StorageException}（fail-visible，防「以为标了实际没写」）</li>
 * </ul>
 */
@Repository
public class RoundBoundaryRepository {

    private static final Logger log = LoggerFactory.getLogger(RoundBoundaryRepository.class);
    private static final String PATH = "trading/round-boundaries.json";
    private static final ObjectMapper MAPPER = StrictJson.strict(new ObjectMapper());

    /** per-user 条带锁（固定 16 条带，P2-交易28 锁池模式）。 */
    private final Object[] lockStripes = new Object[16];

    private final FileStorage fileStorage;

    public RoundBoundaryRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        for (int i = 0; i < lockStripes.length; i++) lockStripes[i] = new Object();
    }

    /** userId → 条带锁。 */
    private Object lockFor(String userId) {
        return lockStripes[(userId != null ? userId.hashCode() : 0) & (lockStripes.length - 1)];
    }

    /** 读取该用户全部人工边界；无文件/损坏 → 空 list（降级不坏）。 */
    public List<RoundBoundary> findByUser(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(content);
            JsonNode arr = root.path("boundaries");
            if (!arr.isArray()) {
                // 结构不认识（如 {"foo":1}）：与解析失败同等待遇——先备份再降级（P2-交易95）
                backupCorrupt(userId, content, null);
                return List.of();
            }
            List<RoundBoundary> result = new ArrayList<>();
            for (JsonNode n : arr) {
                String symbol = n.path("symbol").asText(null);
                String anchorBuyId = n.path("anchorBuyId").asText(null);
                if (symbol == null || symbol.isBlank() || anchorBuyId == null || anchorBuyId.isBlank()) {
                    log.warn("人工边界缺 symbol/anchorBuyId，跳过 | userId={}", userId);
                    continue;
                }
                result.add(new RoundBoundary(symbol, anchorBuyId,
                        n.path("anchorDate").asText(null),
                        n.path("mode").asText(null),
                        n.path("note").asText(null),
                        n.path("updatedAt").asText(null)));
            }
            return result;
        } catch (Exception e) {
            backupCorrupt(userId, content, e);
            return List.of();
        }
    }

    /**
     * 读侧内容不可解析时：**先把原文原样备份**到 {@code .bak-corrupt} 再降级返回空——
     * 防「损坏降级为空 + 写侧全量重写」把残余内容整体盖掉（恢复可能性归零，P2-交易95）。
     * 备份失败只告警、不阻塞读取（降级语义不变——回纯自动口径）。
     */
    private void backupCorrupt(String userId, String content, Exception cause) {
        String bak = PATH + ".bak-corrupt";
        try {
            fileStorage.write(userId, bak, content);
            log.warn("人工边界读不出（视为无人工边界）：原文已备份 → {} | userId={} | {}", bak, userId,
                    cause != null ? cause.getMessage() : "结构不认识（不是 {boundaries:[...]} 形状）");
        } catch (Exception e) {
            log.warn("人工边界读不出（视为无人工边界）且备份失败 | userId={} | {} | 备份失败：{}", userId,
                    cause != null ? cause.getMessage() : "结构不认识", e.getMessage());
        }
    }

    /** 设/改一条人工边界（同 (symbol, anchorBuyId) 覆盖并移到末尾 = 最后操作生效）。写失败抛 StorageException。 */
    public void upsert(String userId, RoundBoundary boundary) {
        synchronized (lockFor(userId)) {
            List<RoundBoundary> all = new ArrayList<>(findByUser(userId));
            all.removeIf(x -> x.symbol().equals(boundary.symbol())
                    && x.anchorBuyId().equals(boundary.anchorBuyId()));
            all.add(boundary);
            write(userId, all);
        }
    }

    /** 清除某锚点的人工边界（切分回到自动让位）；不存在 → 幂等成功。 */
    public void remove(String userId, String symbol, String anchorBuyId) {
        synchronized (lockFor(userId)) {
            List<RoundBoundary> all = new ArrayList<>(findByUser(userId));
            boolean removed = all.removeIf(x -> x.symbol().equals(symbol)
                    && x.anchorBuyId().equals(anchorBuyId));
            if (!removed) return;
            write(userId, all);
        }
    }

    private void write(String userId, List<RoundBoundary> boundaries) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("boundaries");
        for (RoundBoundary b : boundaries) {
            ObjectNode n = arr.addObject();
            n.put("symbol", b.symbol());
            n.put("anchorBuyId", b.anchorBuyId());
            if (b.anchorDate() != null) n.put("anchorDate", b.anchorDate());
            if (b.mode() != null) n.put("mode", b.mode());
            if (b.note() != null) n.put("note", b.note());
            if (b.updatedAt() != null) n.put("updatedAt", b.updatedAt());
        }
        try {
            fileStorage.write(userId, PATH, MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            throw new StorageException(
                    "保存人工边界失败 | userId=" + userId + " | " + e.getMessage(), e);
        }
    }
}
