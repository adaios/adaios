package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.UserRule;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UserRuleRepository — 用户规则集（三态：候选 / 已认 / 自定义；弃掉为墓碑态 DISMISSED）持久化
 * （rules 批，2026-10-06；墓碑 P1-交易93）。
 *
 * <p>落点 {@code data/{userId}/trading/user-rules.json}（File First，用户私有）。与
 * {@code rules.yaml}（参数化阈值，design-final §4.1「不变」）分开存——本文件只存
 * <b>规则条目的文本 + 参数 + 依据 + 三态</b>。
 *
 * <pre>
 * { "rules": [
 *   {"id":"cand-stoploss","state":"CANDIDATE","text":"…","source":"DATA",
 *    "params":{"pct":-3.0},
 *    "evidence":{"how":"…","facts":["…"],"roundIds":["600206_2026-08-05"],"dates":["2026-08-05"]},
 *    "createdAt":"2026-10-06T20:00:00","updatedAt":"2026-10-06T20:00:00"}
 * ]}
 * </pre>
 *
 * <p>语义（与 {@link RoundBoundaryRepository} 同模式）：
 * <ul>
 *   <li>{@link #findByUser}：按文件顺序读全部；无文件 → 空 list；损坏 → **先备份 .bak-corrupt** 再降级空
 *       （降级不坏；备份防「损坏 + 写侧全量重写」丢残余，P2-交易95）</li>
 *   <li>{@link #upsert}：同 id <b>原位替换</b>（保持列表顺序稳定——候选刷新不跳位）；不存在 → 追加末尾</li>
 *   <li>{@link #remove}：删除某条；不存在 → 幂等成功</li>
 *   <li>写失败抛 {@link StorageException}（fail-visible，防「以为认下了实际没写」）</li>
 * </ul>
 */
@Repository
public class UserRuleRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRuleRepository.class);
    private static final String PATH = "trading/user-rules.json";
    private static final ObjectMapper MAPPER = StrictJson.strict(new ObjectMapper());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** per-user 条带锁（固定 16 条带，与 RoundBoundaryRepository 同模式）。 */
    private final Object[] lockStripes = new Object[16];

    private final FileStorage fileStorage;

    public UserRuleRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        for (int i = 0; i < lockStripes.length; i++) lockStripes[i] = new Object();
    }

    private Object lockFor(String userId) {
        return lockStripes[(userId != null ? userId.hashCode() : 0) & (lockStripes.length - 1)];
    }

    /** 读取该用户全部规则（按文件顺序）；无文件 / 损坏 → 空 list（降级不坏）。 */
    public List<UserRule> findByUser(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(content);
            JsonNode arr = root.path("rules");
            if (!arr.isArray()) {
                // 结构不认识（如 {"foo":1}）：与解析失败同等待遇——先备份再降级（P2-交易95）
                backupCorrupt(userId, content, null);
                return List.of();
            }
            List<UserRule> result = new ArrayList<>();
            for (JsonNode n : arr) {
                String id = n.path("id").asText(null);
                String text = n.path("text").asText(null);
                if (id == null || id.isBlank() || text == null || text.isBlank()) {
                    log.warn("规则条目缺 id/text，跳过 | userId={}", userId);
                    continue;
                }
                UserRule.UserRuleState state;
                UserRule.RuleSource source;
                try {
                    state = UserRule.UserRuleState.valueOf(n.path("state").asText("CANDIDATE"));
                    source = UserRule.RuleSource.valueOf(n.path("source").asText("DATA"));
                } catch (IllegalArgumentException e) {
                    log.warn("规则条目状态/来源不认识，跳过 | userId={} | id={}", userId, id);
                    continue;
                }
                result.add(new UserRule(id, state, text, source,
                        readParams(n.path("params")),
                        readEvidence(n.path("evidence")),
                        n.path("createdAt").asText(null),
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
     * 备份失败只告警、不阻塞读取（降级语义不变——写侧会照常在上层触发后重建）。
     */
    private void backupCorrupt(String userId, String content, Exception cause) {
        String bak = PATH + ".bak-corrupt";
        try {
            fileStorage.write(userId, bak, content);
            log.warn("用户规则集读不出（视为空）：原文已备份 → {} | userId={} | {}", bak, userId,
                    cause != null ? cause.getMessage() : "结构不认识（不是 {rules:[...]} 形状）");
        } catch (Exception e) {
            log.warn("用户规则集读不出（视为空）且备份失败 | userId={} | {} | 备份失败：{}", userId,
                    cause != null ? cause.getMessage() : "结构不认识", e.getMessage());
        }
    }

    /** 保存一条（同 id 原位替换、新 id 追加末尾）。写失败抛 StorageException。 */
    public void upsert(String userId, UserRule rule) {
        synchronized (lockFor(userId)) {
            List<UserRule> all = new ArrayList<>(findByUser(userId));
            int idx = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id().equals(rule.id())) { idx = i; break; }
            }
            if (idx >= 0) all.set(idx, rule);
            else all.add(rule);
            write(userId, all);
        }
    }

    /** 删除某条；不存在 → 幂等成功。 */
    public void remove(String userId, String id) {
        synchronized (lockFor(userId)) {
            List<UserRule> all = new ArrayList<>(findByUser(userId));
            boolean removed = all.removeIf(r -> r.id().equals(id));
            if (!removed) return;
            write(userId, all);
        }
    }

    // ── 读写细节 ──

    private static Map<String, Object> readParams(JsonNode n) {
        if (n == null || !n.isObject()) return Map.of();
        return MAPPER.convertValue(n, MAP_TYPE);
    }

    private static UserRule.Evidence readEvidence(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        return new UserRule.Evidence(
                n.path("how").asText(null),
                readStringList(n.path("facts")),
                readStringList(n.path("roundIds")),
                readStringList(n.path("dates")));
    }

    private static List<String> readStringList(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode x : n) {
            String s = x.asText(null);
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }

    private void write(String userId, List<UserRule> rules) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("rules");
        for (UserRule r : rules) {
            ObjectNode n = arr.addObject();
            n.put("id", r.id());
            n.put("state", r.state().name());
            n.put("text", r.text());
            n.put("source", r.source().name());
            if (!r.params().isEmpty()) n.set("params", MAPPER.valueToTree(r.params()));
            if (r.evidence() != null) {
                ObjectNode e = n.putObject("evidence");
                if (r.evidence().how() != null) e.put("how", r.evidence().how());
                e.set("facts", MAPPER.valueToTree(r.evidence().facts()));
                e.set("roundIds", MAPPER.valueToTree(r.evidence().roundIds()));
                e.set("dates", MAPPER.valueToTree(r.evidence().dates()));
            }
            if (r.createdAt() != null) n.put("createdAt", r.createdAt());
            if (r.updatedAt() != null) n.put("updatedAt", r.updatedAt());
        }
        try {
            fileStorage.write(userId, PATH, MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            throw new StorageException("保存用户规则集失败 | userId=" + userId + " | " + e.getMessage(), e);
        }
    }
}
