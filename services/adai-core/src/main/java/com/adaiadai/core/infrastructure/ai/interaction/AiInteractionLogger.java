package com.adaiadai.core.infrastructure.ai.interaction;

import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AiInteractionLogger — AI 交互日志落盘（R1）。
 * <p>
 * File First：JSONL 追加写 {@code data/{userId}/ai-logs/YYYY/MM/ai-log-YYYY-MM-DD.jsonl}，
 * 每行一条 {@link AiInteractionLog}（机器可读，后续管理端可视化直接解析）。
 * <p>
 * 线程安全：HTTP 请求并发时同一日志文件可能被多线程追加，内部 {@code synchronized} 保证
 * 追加串行（配合 {@link FileStorage#append} 的 O_APPEND 单次 write，不丢行、不交错）。
 * <p>
 * 写入失败不阻塞业务（best-effort）：AI 调用已成功，日志只是记录；异常降级为 slf4j 告警。
 * <p>
 * REVIEW #210 隐私治理：日志含 prompt 全文（档案/记忆/持仓明文），
 * <ol>
 *   <li><b>retention</b>：默认保留 30 天（{@code adai.ai-log.retention-days}），写入时惰性清理
 *       （同一用户同一自然日只扫一次）早于保留期的日志文件，防止无限明文堆积</li>
 *   <li><b>读取治理</b>：{@link #readDay} 支持分页 + {@link #countDay} 总数，
 *       {@link #oldestRetainableDate()} 供管理端拒绝查询已过保留期的历史</li>
 * </ol>
 * 保留全文记录能力（回答"提示词怎么组装的"是 R1 的目标），不做 prompt 脱敏开关。
 */
@Component
public class AiInteractionLogger {

    private static final Logger log = LoggerFactory.getLogger(AiInteractionLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LOG_FILE_DATE = Pattern.compile("ai-log-(\\d{4}-\\d{2}-\\d{2})\\.jsonl");

    private final FileStorage fileStorage;
    private final int retentionDays;
    private final Object appendLock = new Object();
    /** 惰性清理标记：每个用户今天是否已清理过（防每次写入都全量扫描）。 */
    private final ConcurrentMap<String, LocalDate> lastCleanupByUser = new ConcurrentHashMap<>();

    public AiInteractionLogger(FileStorage fileStorage,
                               @Value("${adai.ai-log.retention-days:30}") int retentionDays) {
        this.fileStorage = fileStorage;
        this.retentionDays = retentionDays;
    }

    /**
     * 追加一条 AI 交互日志。失败仅告警，不抛出（日志不能拖垮主流程）。
     * 写入后惰性触发过期清理（同一用户同一自然日最多一次）。
     */
    public void log(String userId, AiInteractionLog entry) {
        if (entry == null) return;
        try {
            String line = MAPPER.writeValueAsString(entry) + "\n";
            String path = dayPath(LocalDate.now());
            synchronized (appendLock) {
                fileStorage.append(userId, path, line);
            }
            cleanupIfDayChanged(userId);
        } catch (Exception e) {
            log.warn("AI 交互日志落盘失败 | kind={} | {}", entry.kind(), e.getMessage());
        }
    }

    /**
     * 读取某天的 AI 交互日志（全量，兼容两参调用方）。
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return 日志条目列表（当天无记录返回空列表）
     */
    public List<AiInteractionLog> readDay(String userId, LocalDate date) {
        return readDay(userId, date, 0, Integer.MAX_VALUE);
    }

    /**
     * 分页读取某天的 AI 交互日志（REVIEW #210：单日条数上限/分页）。
     *
     * @param offset 条数偏移（0 起，非页码）
     * @param limit  本页最大条数（&le;0 表示不限）
     * @return 本页条目列表
     */
    public List<AiInteractionLog> readDay(String userId, LocalDate date, int offset, int limit) {
        List<AiInteractionLog> all = parseDay(userId, date);
        int from = Math.min(Math.max(offset, 0), all.size());
        int to = Math.min(from + Math.max(limit, 0), all.size());
        return all.subList(from, to);
    }

    /**
     * 某天日志的有效条目总数（管理端分页返回 total 用）。
     */
    public int countDay(String userId, LocalDate date) {
        return parseDay(userId, date).size();
    }

    /**
     * 保留期窗口起点：早于该日期的日志已被清理/不可查（管理端用它拒绝过期查询）。
     */
    public LocalDate oldestRetainableDate() {
        return LocalDate.now().minusDays(retentionDays);
    }

    /** 保留天数（管理端错误提示用）。 */
    public int retentionDays() {
        return retentionDays;
    }

    // ── 过期清理（#210）──

    /**
     * 惰性清理：同一用户同一自然日最多触发一次全量扫描。
     * 注意 retentionDays &le; 0 表示不清理（保留全部，默认值 30）。
     */
    private void cleanupIfDayChanged(String userId) {
        LocalDate today = LocalDate.now();
        boolean needCleanup;
        synchronized (appendLock) {
            needCleanup = !today.equals(lastCleanupByUser.get(userId));
            if (needCleanup) {
                lastCleanupByUser.put(userId, today);
            }
        }
        if (needCleanup) {
            cleanupExpired(userId, today);
        }
    }

    /**
     * 删除早于保留期的 ai-log 文件（按文件名 {@code ai-log-YYYY-MM-DD.jsonl} 解析日期）。
     * 空月份目录残留无隐私含义，不额外删除（不引入删目录能力）。
     */
    void cleanupExpired(String userId, LocalDate today) {
        if (retentionDays <= 0) return; // 0/负数 = 不清理
        LocalDate cutoff = today.minusDays(retentionDays);
        for (String path : fileStorage.listFiles(userId, "ai-logs")) {
            LocalDate fileDate = dateFromLogPath(path);
            if (fileDate != null && fileDate.isBefore(cutoff)) {
                try {
                    fileStorage.delete(userId, path);
                    log.info("AI 交互日志过期清理 | userId={} | file={}", userId, path);
                } catch (Exception e) {
                    log.warn("AI 日志过期清理失败 | userId={} | file={} | {}", userId, path, e.getMessage());
                }
            }
        }
    }

    private LocalDate dateFromLogPath(String path) {
        Matcher m = LOG_FILE_DATE.matcher(path);
        if (!m.find()) return null;
        try {
            return LocalDate.parse(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    // ── 读取 ──

    private List<AiInteractionLog> parseDay(String userId, LocalDate date) {
        String path = dayPath(date);
        String content;
        try {
            content = fileStorage.read(userId, path);
        } catch (Exception e) {
            log.warn("AI 交互日志读取失败 | path={} | {}", path, e.getMessage());
            return List.of();
        }
        if (content == null || content.isBlank()) return List.of();

        List<AiInteractionLog> entries = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            try {
                entries.add(MAPPER.readValue(line, AiInteractionLog.class));
            } catch (Exception e) {
                log.warn("AI 交互日志行解析失败，跳过 | {}", e.getMessage());
            }
        }
        return entries;
    }

    private String dayPath(LocalDate date) {
        return String.format("ai-logs/%04d/%02d/ai-log-%s.jsonl",
                date.getYear(), date.getMonthValue(),
                date.format(DateTimeFormatter.ISO_LOCAL_DATE));
    }
}
