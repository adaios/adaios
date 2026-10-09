package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.cases.CaseCandidate;
import com.adaiadai.core.domain.trading.cases.CaseCandidateRepository;
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
 * CaseCandidateFileRepository — 案例候选「你的决定」JSON 存储（2026-10-08，案例候选批）。
 *
 * <p>文件 {@code data/{userId}/trading/case-candidates.json}（File First，可 diff/可回滚）：
 * <pre>
 * { "records": [ {"id":"sell-603993-2026-08-19","state":"ACCEPTED", ...} ] }
 * </pre>
 * <b>只存有状态的条目</b>（ACCEPTED / DISMISSED）——候选本身每次现算不落盘：数据没变、
 * 你没做决定，文件就不变（文件 = 你的决定的真相源）。
 *
 * <p>读取宽容（对齐 UserRuleRepository）：缺 id/symbol/date 的行跳过；state/kind/outcome
 * 不认识的行跳过；ruleRel 不认识 → 归 null（对照丢了不毁掉案例主体）；整体不可解析 →
 * 原文备份 {@code .bak-corrupt} 后降级为空（P2-交易95：防「损坏降级为空 + 写侧全量重写」
 * 把残余内容整体盖掉）。并发：per-user 条带锁（固定 16 条带）串行读-改-写。
 */
@Repository
public class CaseCandidateFileRepository implements CaseCandidateRepository {

    private static final Logger log = LoggerFactory.getLogger(CaseCandidateFileRepository.class);
    private static final String PATH = "trading/case-candidates.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** per-user 条带锁（固定 16 条带，与 UserRuleRepository / RoundBoundaryRepository 同模式）。 */
    private final Object[] lockStripes = new Object[16];

    private final FileStorage fileStorage;

    public CaseCandidateFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        for (int i = 0; i < lockStripes.length; i++) lockStripes[i] = new Object();
    }

    private Object lockFor(String userId) {
        return lockStripes[(userId != null ? userId.hashCode() : 0) & (lockStripes.length - 1)];
    }

    @Override
    public List<CaseCandidate> findByUser(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(content);
            JsonNode arr = root.path("records");
            if (!arr.isArray()) {
                backupCorrupt(userId, content, null);
                return List.of();
            }
            List<CaseCandidate> result = new ArrayList<>();
            for (JsonNode n : arr) {
                CaseCandidate c = readOne(n);
                if (c != null) result.add(c);
            }
            return result;
        } catch (Exception e) {
            backupCorrupt(userId, content, e);
            return List.of();
        }
    }

    private CaseCandidate readOne(JsonNode n) {
        String id = n.path("id").asText(null);
        String symbol = n.path("symbol").asText(null);
        String date = n.path("date").asText(null);
        if (id == null || id.isBlank() || symbol == null || symbol.isBlank()
                || date == null || date.isBlank()) {
            log.warn("案例候选条目缺 id/symbol/date，跳过");
            return null;
        }
        CaseCandidate.State state;
        CaseCandidate.Kind kind;
        CaseCandidate.Outcome outcome;
        try {
            state = CaseCandidate.State.valueOf(n.path("state").asText("CANDIDATE"));
            kind = CaseCandidate.Kind.valueOf(n.path("kind").asText(""));
            outcome = CaseCandidate.Outcome.valueOf(n.path("outcome").asText(""));
        } catch (IllegalArgumentException e) {
            log.warn("案例候选条目状态/类别不认识，跳过 | id={}", id);
            return null;
        }
        // ruleRel 读不出 → null（对照丢了不毁案例主体——宽容，与其余字段的 skip 口径不同）
        CaseCandidate.RuleRel rel = null;
        String relText = n.path("ruleRel").asText(null);
        if (relText != null && !relText.isBlank()) {
            try {
                rel = CaseCandidate.RuleRel.valueOf(relText);
            } catch (IllegalArgumentException e) {
                log.warn("案例候选对照方向不认识，按无对照处理 | id={}", id);
            }
        }
        Double changePct = n.path("changePct").isNumber() ? n.path("changePct").asDouble() : null;
        return new CaseCandidate(id, state, kind, outcome, symbol,
                n.path("name").asText(null), date, n.path("title").asText(null), changePct, rel,
                n.path("ruleId").asText(null), n.path("ruleText").asText(null),
                readStringList(n.path("notes")),
                n.path("createdAt").asText(null), n.path("updatedAt").asText(null));
    }

    /**
     * 读侧内容不可解析时：先把原文原样备份到 {@code .bak-corrupt} 再降级返回空——
     * 防「损坏降级为空 + 写侧全量重写」把残余内容整体盖掉（同 UserRuleRepository P2-交易95）。
     */
    private void backupCorrupt(String userId, String content, Exception cause) {
        String bak = PATH + ".bak-corrupt";
        try {
            fileStorage.write(userId, bak, content);
            log.warn("案例候选决定读不出（视为空）：原文已备份 → {} | userId={} | {}", bak, userId,
                    cause != null ? cause.getMessage() : "结构不认识（不是 {records:[...]} 形状）");
        } catch (Exception e) {
            log.warn("案例候选决定读不出（视为空）且备份失败 | userId={} | {} | 备份失败：{}", userId,
                    cause != null ? cause.getMessage() : "结构不认识", e.getMessage());
        }
    }

    @Override
    public void upsert(String userId, CaseCandidate record) {
        synchronized (lockFor(userId)) {
            List<CaseCandidate> all = new ArrayList<>(findByUser(userId));
            int idx = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id().equals(record.id())) { idx = i; break; }
            }
            if (idx >= 0) all.set(idx, record);
            else all.add(record);
            write(userId, all);
        }
    }

    // ── 读写细节 ──

    private static List<String> readStringList(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode x : n) {
            String s = x.asText(null);
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }

    private void write(String userId, List<CaseCandidate> records) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("records");
        for (CaseCandidate c : records) {
            ObjectNode n = arr.addObject();
            n.put("id", c.id());
            n.put("state", c.state().name());
            n.put("kind", c.kind().name());
            n.put("outcome", c.outcome().name());
            n.put("symbol", c.symbol());
            if (c.name() != null) n.put("name", c.name());
            n.put("date", c.date());
            if (c.title() != null) n.put("title", c.title());
            if (c.changePct() != null) n.put("changePct", c.changePct());
            if (c.ruleRel() != null) n.put("ruleRel", c.ruleRel().name());
            if (c.ruleId() != null) n.put("ruleId", c.ruleId());
            if (c.ruleText() != null) n.put("ruleText", c.ruleText());
            if (!c.notes().isEmpty()) n.set("notes", MAPPER.valueToTree(c.notes()));
            if (c.createdAt() != null) n.put("createdAt", c.createdAt());
            if (c.updatedAt() != null) n.put("updatedAt", c.updatedAt());
        }
        try {
            fileStorage.write(userId, PATH, MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            throw new StorageException(
                    "保存案例候选决定失败 | userId=" + userId + " | " + e.getMessage(), e);
        }
    }
}
