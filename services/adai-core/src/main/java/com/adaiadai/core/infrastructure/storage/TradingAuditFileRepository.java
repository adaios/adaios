package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingAuditRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * TradingAuditFileRepository — 修改日志文件实现（设计 §4.3，2026-10-06）。
 *
 * <p>落点 {@code data/{userId}/trading/audit/{yyyy-MM}.jsonl}——**每行一条 JSON**（jsonl）：
 * 追加 = 读现有内容 + 拼一行 + 原子覆盖写回（个人日志体量小，读改写可接受）。
 * 失败**不吞**：解析/写入异常一律抛 {@link StorageException}（fail-visible，调用方不得静默继续）。
 *
 * <p>2026-10-06（deep 审查修复批）：
 * <ul>
 *   <li><b>P2-交易97</b>：追加是读改写——加 per-user 条带锁（与 RoundBoundaryRepository 同模式），
 *       防同用户并发（双端同时改账）后写覆盖前写、丢条目</li>
 *   <li><b>P2-交易99</b>：月份落点用注入 {@link Clock}（不再直读系统时钟）——跨月边界明确、测试可注入</li>
 *   <li><b>P2-交易96</b>：新增 {@link #appendAll}（整批一次读改写——多字段留痕要么全有要么全无）</li>
 * </ul>
 */
@Repository
public class TradingAuditFileRepository implements TradingAuditRepository {

    private static final Logger log = LoggerFactory.getLogger(TradingAuditFileRepository.class);

    private static final String AUDIT_DIR = "trading/audit";
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;
    /** 月份落点时钟（P2-交易99：可注入，不再看真实时钟）。 */
    private final Clock clock;

    /** per-user 条带锁（固定 16 条带，与 RoundBoundaryRepository 同模式）：append 是读改写，
     *  同用户并发（双端同时改账）不加锁会丢条目（P2-交易97）。 */
    private final Object[] lockStripes = new Object[16];

    public TradingAuditFileRepository(FileStorage fileStorage, Clock clock) {
        this.fileStorage = fileStorage;
        this.clock = clock;
        for (int i = 0; i < lockStripes.length; i++) lockStripes[i] = new Object();
        this.objectMapper = StrictJson.strict(new ObjectMapper())
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private Object lockFor(String userId) {
        return lockStripes[(userId != null ? userId.hashCode() : 0) & (lockStripes.length - 1)];
    }

    @Override
    public void append(String userId, AuditEntry entry) {
        appendAll(userId, List.of(entry));
    }

    /**
     * 批量追加（P2-交易96）：整批**一次读改写**落盘——多字段纠错/导入的留痕要么全有要么全无，
     * 不留「部分字段有留痕、账未改」的半截孤儿。
     * <p>先整批序列化（任何一条失败 → 整批中止、未写盘），再进锁读改写（锁窗口最小化）。
     */
    @Override
    public void appendAll(String userId, List<AuditEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        String path = filePath(LocalDate.now(clock));
        StringBuilder lines = new StringBuilder();
        for (AuditEntry entry : entries) {
            try {
                lines.append(objectMapper.writeValueAsString(entry)).append('\n');
            } catch (Exception e) {
                throw new StorageException("修改日志序列化失败: " + path, e);
            }
        }
        String batch = lines.toString();
        synchronized (lockFor(userId)) {
            String existing = fileStorage.read(userId, path);
            String content = (existing == null || existing.isBlank())
                    ? batch : existing.stripTrailing() + "\n" + batch;
            try {
                fileStorage.write(userId, path, content);
            } catch (RuntimeException e) {
                // fail-visible：审计写不进 → 抛给调用方（业务不得静默成功）
                throw new StorageException("修改日志写入失败（业务已中止，未落账）: " + path, e);
            }
            for (AuditEntry entry : entries) {
                log.info("修改日志已落盘 | userId={} | path={} | {} | {}: {} → {} | {}",
                        userId, path, entry.recordId(), entry.field(), entry.before(), entry.after(), entry.source());
            }
        }
    }

    @Override
    public List<AuditEntry> findAll(String userId) {
        List<AuditEntry> all = new ArrayList<>();
        List<String> paths = new ArrayList<>(fileStorage.listFiles(userId, AUDIT_DIR));
        paths.sort(String::compareTo);
        for (String path : paths) {
            if (path == null || !path.endsWith(".jsonl")) continue;
            String content = fileStorage.read(userId, path);
            if (content == null || content.isBlank()) continue;
            for (String line : content.split("\n")) {
                if (line.isBlank()) continue;
                try {
                    all.add(objectMapper.readValue(line, AuditEntry.class));
                } catch (Exception e) {
                    // 审计日志读侧容忍单行损坏（如实记录，不阻塞复算）
                    log.warn("修改日志单行解析失败（跳过）| userId={} | path={} | {}", userId, path, e.getMessage());
                }
            }
        }
        all.sort(java.util.Comparator.comparing(AuditEntry::time,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return all;
    }

    private String filePath(LocalDate date) {
        return AUDIT_DIR + "/" + date.format(MONTH_FMT) + ".jsonl";
    }
}
