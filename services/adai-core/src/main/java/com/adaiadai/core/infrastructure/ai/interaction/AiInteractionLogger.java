package com.adaiadai.core.infrastructure.ai.interaction;

import com.adaiadai.core.infrastructure.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
 */
@Component
public class AiInteractionLogger {

    private static final Logger log = LoggerFactory.getLogger(AiInteractionLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileStorage fileStorage;
    private final Object appendLock = new Object();

    public AiInteractionLogger(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 追加一条 AI 交互日志。失败仅告警，不抛出（日志不能拖垮主流程）。
     */
    public void log(String userId, AiInteractionLog entry) {
        if (entry == null) return;
        try {
            String line = MAPPER.writeValueAsString(entry) + "\n";
            String path = dayPath(LocalDate.now());
            synchronized (appendLock) {
                fileStorage.append(userId, path, line);
            }
        } catch (Exception e) {
            log.warn("AI 交互日志落盘失败 | kind={} | {}", entry.kind(), e.getMessage());
        }
    }

    /**
     * 读取某天的 AI 交互日志（按写入顺序）。文件不存在或某行解析失败时跳过（不中断整读）。
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return 日志条目列表（当天无记录返回空列表）
     */
    public List<AiInteractionLog> readDay(String userId, LocalDate date) {
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
