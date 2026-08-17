package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.project.Task;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.adaiadai.core.domain.project.TaskRepository;
import com.adaiadai.core.domain.project.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ProjectFileRepository — 基于文件系统的任务存储实现。
 * <p>
 * 任务数据按月组织到 {@code data/project/tasks/YYYY/MM.md}。
 * 采用 File First：纯文本，人类和 AI 都可直接阅读。
 * <pre>
 * # 任务 - 2026-07
 *
 * ---
 * id: task_20260726_123456
 * title: 实现轻量任务系统
 * description: 创建 Task 领域模型和相关存储
 * status: DOING
 * priority: P0
 * tags: [后端, 架构]
 * rfcRef: 20260725-layer6
 * createdAt: 2026-07-26
 * updatedAt: 2026-07-26
 * ---
 * </pre>
 */
@Repository
public class ProjectFileRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(ProjectFileRepository.class);

    private static final String TASKS_DIR = "project/tasks";
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "---\\n" +
                    // 注意：冒号后空白用 [ \t]* 而非 \\s*——\\s 含换行，字段为空时（如 rfcRef 空）
                    // 会贪婪吞掉下一行导致字段错位（R2 实测：rfcRef: 空行吞掉 sourceRecordId 行）
                    "id:[ \\t]*(\\S+)\\n" +
                    "title:[ \\t]*([^\\n]*)\\n" +
                    "description:[ \\t]*([^\\n]*)\\n" +
                    "status:[ \\t]*(\\S+)\\n" +
                    "priority:[ \\t]*(\\S+)\\n" +
                    "tags:[ \\t]*\\[([^\\]]*)\\]\\n" +
                    "rfcRef:[ \\t]*([^\\n]*)\\n" +
                    // R2：sourceRecordId 为可选行（旧任务文件无此行 → group(8) 为 null，向后兼容）
                    "(?:sourceRecordId:[ \\t]*([^\\n]*)\\n)?" +
                    "createdAt:[ \\t]*(\\S+)\\n" +
                    "updatedAt:[ \\t]*(\\S+)\\n" +
                    "---\\n" +
                    ".+?(?=\\n---|\\z)",
            Pattern.DOTALL);

    private final FileStorage fileStorage;

    public ProjectFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public List<Task> findAll(TaskStatus status, String tag, String userId) {
        return findAll(userId).stream()
                .filter(t -> status == null || t.status() == status)
                .filter(t -> tag == null || tag.isBlank() || t.tags().contains(tag))
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findAll(String userId) {
        List<Task> all = new ArrayList<>();
        // 扫描全部月份文件（不限 12 个月，任务生命周期可能更长）
        List<String> files = fileStorage.listFiles(userId, TASKS_DIR);
        for (String path : files) {
            if (!path.endsWith(".md")) continue;
            String content = fileStorage.read(userId, path);
            if (content != null && !content.isBlank()) {
                all.addAll(parseEntries(content));
            }
        }
        return all;
    }

    @Override
    public Optional<Task> findById(String userId, String id) {
        return findAll(userId).stream()
                .filter(t -> t.id().equals(id))
                .findFirst();
    }

    @Override
    public synchronized void save(String userId, Task task) {
        String path = taskFilePath(task.createdAt());
        String entry = formatTaskEntry(task);

        String existing = fileStorage.read(userId, path);
        // 尝试更新已存在的条目
        if (existing != null && !existing.isBlank()) {
            List<Task> tasks = parseEntries(existing);
            boolean replaced = false;
            StringBuilder sb = new StringBuilder();
            // 保留文件头（第一行 # 标题 + 手写注释，到第一个条目前）
            sb.append(extractHeader(existing)).append("\n\n");

            for (Task t : tasks) {
                if (t.id().equals(task.id())) {
                    sb.append(entry).append("\n");
                    replaced = true;
                } else {
                    sb.append(formatTaskEntry(t)).append("\n");
                }
            }
            if (!replaced) {
                sb.append(entry).append("\n");
            }
            fileStorage.write(userId, path, sb.toString());
        } else {
            String content = """
                    # 任务 - %s

                    %s
                    """.formatted(task.createdAt().toString(), entry);
            fileStorage.write(userId, path, content);
        }
        log.info("任务已保存 | id={} | title={} | status={}", task.id(), task.title(), task.status());
    }

    @Override
    public synchronized void delete(String userId, String id) { // P2-7（2026-08-17 走查）：与 save 同锁，防并发 save+delete 互覆
        Task task = findById(userId, id).orElse(null);
        if (task == null) {
            log.warn("任务删除失败：未找到 | id={}", id);
            return;
        }

        String path = taskFilePath(task.createdAt());
        String existing = fileStorage.read(userId, path);
        if (existing == null || existing.isBlank()) return;

        List<Task> tasks = parseEntries(existing);
        StringBuilder sb = new StringBuilder();
        // 保留文件头（第一行 # 标题 + 手写注释，到第一个条目前）
        sb.append(extractHeader(existing)).append("\n\n");

        for (Task t : tasks) {
            if (!t.id().equals(id)) {
                sb.append(formatTaskEntry(t)).append("\n");
            }
        }
        fileStorage.write(userId, path, sb.toString());
        log.info("任务已删除 | id={}", id);
    }

    @Override
    public TaskStats stats(String userId) {
        List<Task> all = findAll(userId);
        return new TaskStats(
                all.size(),
                (int) all.stream().filter(t -> t.status() == TaskStatus.TODO).count(),
                (int) all.stream().filter(t -> t.status() == TaskStatus.DOING).count(),
                (int) all.stream().filter(t -> t.status() == TaskStatus.DONE).count(),
                (int) all.stream().filter(t -> t.status() == TaskStatus.CANCELLED).count()
        );
    }

    // ── 内部方法 ──

    private String taskFilePath(LocalDate date) {
        String ym = date.format(MONTH_FORMATTER);
        return TASKS_DIR + "/" + ym + ".md";
    }

    private String formatTaskEntry(Task task) {
        String title = singleLine(task.title());
        String description = singleLine(task.description());
        return """
                ---
                id: %s
                title: %s
                description: %s
                status: %s
                priority: %s
                tags: [%s]
                rfcRef: %s
                sourceRecordId: %s
                createdAt: %s
                updatedAt: %s
                ---
                %s
                """.strip().formatted(
                task.id(),
                title,
                description,
                task.status().name(),
                task.priority(),
                String.join(", ", task.tags()),
                task.rfcRef() != null ? task.rfcRef() : "",
                task.sourceRecordId() != null ? task.sourceRecordId() : "",
                task.createdAt().toString(),
                task.updatedAt().toString(),
                title
        );
    }

    /**
     * 字段值单行化：换行/回车替换为空格，连续空格压缩。
     * 防止多行 title/description 破坏条目格式（history: 7-30 多行 title 写坏 07.md，6146 行重复堆积）。
     */
    private String singleLine(String s) {
        if (s == null || s.isBlank()) return "";
        return s.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").strip();
    }

    /**
     * 提取文件头：第一行 # 标题 + 其后到第一个条目（---）之间的手写注释。
     * 防止 save/delete 重建文件时丢弃用户手动添加的说明文字（REVIEW #21）。
     */
    private String extractHeader(String content) {
        int idx = content.indexOf("\n---");
        if (idx <= 0) {
            // 无条目或格式异常：退回第一行
            return content.lines().findFirst().orElse("");
        }
        return content.substring(0, idx).strip();
    }

    private List<Task> parseEntries(String content) {
        List<Task> result = new ArrayList<>();
        Matcher matcher = ENTRY_PATTERN.matcher(content);
        while (matcher.find()) {
            try {
                String id = matcher.group(1);
                String title = matcher.group(2).strip();
                String description = matcher.group(3).strip();
                String statusStr = matcher.group(4);
                String priority = matcher.group(5);
                List<String> tags = parseTags(matcher.group(6));
                String rfcRef = matcher.group(7).strip();
                // group(8) 可选：旧任务文件无 sourceRecordId 行时为 null
                String sourceRecordId = matcher.group(8);
                LocalDate createdAt = LocalDate.parse(matcher.group(9));
                LocalDate updatedAt = LocalDate.parse(matcher.group(10));

                TaskStatus status;
                try {
                    status = TaskStatus.valueOf(statusStr);
                } catch (Exception e) {
                    log.warn("未知任务状态: {}，默认 TODO", statusStr);
                    status = TaskStatus.TODO;
                }

                result.add(new Task(id, title, description, status, priority, tags,
                        rfcRef.isEmpty() ? null : rfcRef,
                        sourceRecordId != null ? sourceRecordId.strip() : null,
                        createdAt, updatedAt));
            } catch (Exception e) {
                log.warn("解析任务条目失败: {}", e.getMessage());
            }
        }
        return result;
    }

    private List<String> parseTags(String tagsStr) {
        String cleaned = tagsStr.replaceAll("[\\[\\]\"'\\s]", "");
        if (cleaned.isBlank()) return List.of();
        return Arrays.stream(cleaned.split(","))
                .filter(s -> !s.isBlank())
                .map(String::strip)
                .toList();
    }
}
