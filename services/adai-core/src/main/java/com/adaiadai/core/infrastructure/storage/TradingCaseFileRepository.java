package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.domain.trading.cases.TradingCaseRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * TradingCaseFileRepository — 完美买点案例 JSON 存储（2026-08-30 第四阶段）。
 * <p>
 * 文件布局 {@code data/{userId}/trading/cases/}（File First，可 diff/可回滚）：
 * <pre>
 * _index.json                       # 清单（轻量摘要，列表/检索用）
 * 2026-08-03_000725.json            # 案例真相源（CaseRecord 全量，Jackson 序列化）
 * </pre>
 * 设计：JSON 对齐项目数据惯例（trades/push/account 均为 json，freeze 2.13-2.15）；
 * 案例是机器消费数据，Jackson 直接映射嵌套 record，比 YAML 手写转换更可靠。
 * 并发：per-user 条带锁（固定 16 条带，P2-交易28 锁池模式）串行读-改-写。
 * 写失败抛 StorageException（fail-visible，P0-1 原则）；跨文件一致性（case+index）
 * 无原子手段为已知取舍（trading-features §八 注意点 11）。
 */
@Repository
public class TradingCaseFileRepository implements TradingCaseRepository {

    private static final Logger log = LoggerFactory.getLogger(TradingCaseFileRepository.class);
    private static final String CASES_DIR = "trading/cases/";
    private static final String INDEX_PATH = CASES_DIR + "_index.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final int LOCK_STRIPES = 16;
    private final Object[] locks = new Object[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private final FileStorage fileStorage;

    public TradingCaseFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    private Object lockFor(String key) {
        int h = (key != null ? key : "default").hashCode();
        return locks[(h ^ (h >>> 16)) & (locks.length - 1)];
    }

    @Override
    public Optional<CaseRecord> findById(String userId, String caseId) {
        String content = fileStorage.read(userId, CASES_DIR + caseId + ".json");
        if (content == null || content.isBlank()) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(content, CaseRecord.class));
        } catch (Exception e) {
            log.warn("案例读取失败（视为不存在）| userId={} | caseId={} | {}", userId, caseId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<CaseRecord> list(String userId) {
        List<CaseRecord> result = new ArrayList<>();
        String index = fileStorage.read(userId, INDEX_PATH);
        if (index == null || index.isBlank()) return result;
        try {
            JsonNode root = MAPPER.readTree(index);
            if (!root.isArray()) return result;
            for (JsonNode node : root) {
                String id = node.path("id").asText("");
                if (id.isBlank()) continue;
                findById(userId, id).ifPresent(result::add);
            }
        } catch (Exception e) {
            log.warn("案例清单读取失败（回落空）| userId={} | {}", userId, e.getMessage());
            return List.of();
        }
        result.sort(Comparator.comparing(CaseRecord::buyDate).reversed());
        return result;
    }

    @Override
    public void save(String userId, CaseRecord record) {
        if (record == null || record.id() == null || record.id().isBlank()) {
            throw new StorageException("案例 id 缺失，拒绝保存 | userId=" + userId);
        }
        synchronized (lockFor(userId)) {
            String casePath = CASES_DIR + record.id() + ".json";
            try {
                fileStorage.write(userId, casePath,
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(record));
                upsertIndex(userId, record);
            } catch (Exception e) {
                // S1（2026-08-30 审查）：index 写失败必须回滚刚写的案例文件——
                // 否则文件残留 + 下次标注 exists(文件)=true → 409「已标注过」卡死（无路可走）。
                // 注意：StorageException 也走这里（index 写失败抛 StorageException），不能提前重抛。
                try {
                    fileStorage.delete(userId, casePath);
                } catch (Exception ignore) {
                    // 回滚失败也继续抛原始错误（文件残留由 list 降级/人工清理兜底）
                }
                if (e instanceof StorageException se) {
                    throw se;
                }
                throw new StorageException("保存案例失败 | userId=" + userId + " | " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void delete(String userId, String caseId) {
        synchronized (lockFor(userId)) {
            try {
                removeFromIndex(userId, caseId);
                fileStorage.delete(userId, CASES_DIR + caseId + ".json");
            } catch (StorageException e) {
                throw e;
            } catch (Exception e) {
                throw new StorageException("删除案例失败 | userId=" + userId + " | " + e.getMessage(), e);
            }
        }
    }

    @Override
    public boolean exists(String userId, String caseId) {
        return fileStorage.exists(userId, CASES_DIR + caseId + ".json");
    }

    /** 清单 upsert（读-改-写，锁内原子；损坏 → 重建仅本条目）。 */
    private void upsertIndex(String userId, CaseRecord record) throws Exception {
        List<JsonNode> entries = readIndex(userId);
        entries.removeIf(n -> record.id().equals(n.path("id").asText("")));
        entries.add(indexEntry(record));
        writeIndex(userId, entries);
    }

    /**
     * 重建清单（2026-08-31）：以案例文件为准全量重建 _index.json——
     * 修复「文件类型已补标/verify 已回填但清单摘要过期」的不一致。
     * 直接扫目录（不依赖旧 index），损坏/不可读文件跳过（不中断重建）。
     */
    @Override
    public void rebuildIndex(String userId) {
        synchronized (lockFor(userId)) {
            try {
                List<JsonNode> entries = new ArrayList<>();
                for (String path : fileStorage.listFiles(userId, CASES_DIR)) {
                    String file = path.substring(path.lastIndexOf('/') + 1);
                    if (!file.endsWith(".json") || file.startsWith("_")) continue;
                    String caseId = file.substring(0, file.length() - 5);
                    findById(userId, caseId).ifPresent(c -> entries.add(indexEntry(c)));
                }
                entries.sort(Comparator.comparing((com.fasterxml.jackson.databind.JsonNode n)
                        -> n.path("buyDate").asText("")).reversed());
                writeIndex(userId, entries);
                log.info("案例清单已重建 | userId={} | {} 条", userId, entries.size());
            } catch (Exception e) {
                log.error("案例清单重建失败 | userId={} | {}", userId, e.getMessage());
                throw new StorageException("案例清单重建失败 | userId=" + userId + " | " + e.getMessage(), e);
            }
        }
    }

    private void removeFromIndex(String userId, String caseId) throws Exception {
        List<JsonNode> entries = readIndex(userId);
        entries.removeIf(n -> caseId.equals(n.path("id").asText("")));
        writeIndex(userId, entries);
    }

    private List<JsonNode> readIndex(String userId) {
        List<JsonNode> entries = new ArrayList<>();
        String index = fileStorage.read(userId, INDEX_PATH);
        if (index == null || index.isBlank()) return entries;
        try {
            JsonNode root = MAPPER.readTree(index);
            if (root.isArray()) root.forEach(entries::add);
        } catch (Exception e) {
            log.warn("案例清单损坏，重建 | userId={} | {}", userId, e.getMessage());
        }
        return entries;
    }

    private JsonNode indexEntry(CaseRecord record) {
        return MAPPER.createObjectNode()
                .put("id", record.id())
                .put("symbol", record.symbol())
                .put("name", record.name() == null ? "" : record.name())
                .put("buyDate", record.buyDate() == null ? "" : record.buyDate().toString())
                .put("buyType", record.buyType() == null ? "" : record.buyType())
                .put("plus5dReturnPct",
                        record.verify() == null || record.verify().plus5dReturnPct() == null
                                ? java.math.BigDecimal.ZERO.doubleValue()
                                : record.verify().plus5dReturnPct());
    }

    private void writeIndex(String userId, List<JsonNode> entries) throws Exception {
        fileStorage.write(userId, INDEX_PATH,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entries));
    }
}
