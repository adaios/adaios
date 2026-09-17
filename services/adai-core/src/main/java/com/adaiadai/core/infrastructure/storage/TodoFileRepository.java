package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.storage.FileStorage;
import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoRepository;
import com.adaiadai.core.kernel.todo.TodoStatus;
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
 * TodoFileRepository — 基于文件系统的待办存储实现（RFC 20260917）。
 * <p>
 * 待办数据按月组织到 {@code data/{userId}/todos/YYYY/MM.md}。
 * 采用 File First：纯文本，人类和 AI 都可直接阅读。
 * <pre>
 * # 待办 - 2026-09
 *
 * ---
 * id: todo_20260917_223000123
 * title: 给妈打个电话
 * status: OPEN
 * due: 2026-09-20
 * sourceRecordId: rec_20260917_221500100
 * createdAt: 2026-09-17
 * updatedAt: 2026-09-17
 * ---
 * 给妈打个电话
 * </pre>
 * 兼容性：{@code due} / {@code sourceRecordId} 均为可选行（手写或降级产物缺行不炸）；
 * 旧状态值（DOING / CANCELLED）读作 OPEN（老 project/tasks 数据不迁移，此处只做稳健兜底）。
 */
@Repository
public class TodoFileRepository implements TodoRepository {

    private static final Logger log = LoggerFactory.getLogger(TodoFileRepository.class);

    private static final String TODOS_DIR = "todos";
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "---\\n" +
                    // 注意：冒号后空白用 [ \t]* 而非 \\s*——\\s 含换行，字段为空时会贪婪吞掉下一行导致字段错位
                    "id:[ \\t]*(\\S+)\\n" +
                    "title:[ \\t]*([^\\n]*)\\n" +
                    "status:[ \\t]*(\\S+)\\n" +
                    // due / sourceRecordId 为可选行（group(4)/group(5) 缺失时为 null）
                    "(?:due:[ \\t]*([^\\n]*)\\n)?" +
                    "(?:sourceRecordId:[ \\t]*([^\\n]*)\\n)?" +
                    "createdAt:[ \\t]*(\\S+)\\n" +
                    "updatedAt:[ \\t]*(\\S+)\\n" +
                    "---\\n" +
                    ".+?(?=\\n---|\\z)",
            Pattern.DOTALL);

    private final FileStorage fileStorage;

    public TodoFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public List<Todo> findAll(TodoStatus status, String userId) {
        return findAll(userId).stream()
                .filter(t -> status == null || t.status() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<Todo> findAll(String userId) {
        List<Todo> all = new ArrayList<>();
        // 扫描全部月份文件（不限 12 个月，待办生命周期可能更长）
        List<String> files = fileStorage.listFiles(userId, TODOS_DIR);
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
    public Optional<Todo> findById(String userId, String id) {
        return findAll(userId).stream()
                .filter(t -> t.id().equals(id))
                .findFirst();
    }

    @Override
    public synchronized void save(String userId, Todo todo) {
        String path = todoFilePath(todo.createdAt());
        String entry = formatTodoEntry(todo);

        String existing = fileStorage.read(userId, path);
        if (existing != null && !existing.isBlank()) {
            List<Todo> todos = parseEntries(existing);
            boolean replaced = false;
            StringBuilder sb = new StringBuilder();
            // 保留文件头（第一行 # 标题 + 手写注释，到第一个条目前）
            sb.append(extractHeader(existing)).append("\n\n");

            for (Todo t : todos) {
                if (t.id().equals(todo.id())) {
                    sb.append(entry).append("\n");
                    replaced = true;
                } else {
                    sb.append(formatTodoEntry(t)).append("\n");
                }
            }
            if (!replaced) {
                sb.append(entry).append("\n");
            }
            fileStorage.write(userId, path, sb.toString());
        } else {
            String content = """
                    # 待办 - %s

                    %s
                    """.formatted(monthLabel(todo.createdAt()), entry);
            fileStorage.write(userId, path, content);
        }
        log.info("待办已保存 | id={} | title={} | status={} | due={}", todo.id(), todo.title(),
                todo.status(), todo.due() != null ? todo.due() : "-");
    }

    @Override
    public synchronized void delete(String userId, String id) { // 与 save 同锁，防并发 save+delete 互覆
        Todo todo = findById(userId, id).orElse(null);
        if (todo == null) {
            log.warn("待办删除失败：未找到 | id={}", id);
            return;
        }

        String path = todoFilePath(todo.createdAt());
        String existing = fileStorage.read(userId, path);
        if (existing == null || existing.isBlank()) return;

        List<Todo> todos = parseEntries(existing);
        StringBuilder sb = new StringBuilder();
        sb.append(extractHeader(existing)).append("\n\n");

        for (Todo t : todos) {
            if (!t.id().equals(id)) {
                sb.append(formatTodoEntry(t)).append("\n");
            }
        }
        fileStorage.write(userId, path, sb.toString());
        log.info("待办已删除 | id={}", id);
    }

    @Override
    public TodoStats stats(String userId) {
        List<Todo> all = findAll(userId);
        return new TodoStats(
                all.size(),
                (int) all.stream().filter(t -> t.status() == TodoStatus.OPEN).count(),
                (int) all.stream().filter(t -> t.status() == TodoStatus.DONE).count()
        );
    }

    // ── 内部方法 ──

    private String todoFilePath(LocalDate date) {
        return TODOS_DIR + "/" + date.format(MONTH_FORMATTER) + ".md";
    }

    private String monthLabel(LocalDate date) {
        return date.format(MONTH_FORMATTER);
    }

    private String formatTodoEntry(Todo todo) {
        String title = singleLine(todo.title());
        return """
                ---
                id: %s
                title: %s
                status: %s
                due: %s
                sourceRecordId: %s
                createdAt: %s
                updatedAt: %s
                ---
                %s
                """.strip().formatted(
                todo.id(),
                title,
                todo.status().name(),
                todo.due() != null ? todo.due().toString() : "",
                todo.sourceRecordId() != null ? todo.sourceRecordId() : "",
                todo.createdAt().toString(),
                todo.updatedAt().toString(),
                title
        );
    }

    /**
     * 字段值单行化：换行/回车替换为空格，连续空格压缩。
     * 防止多行 title 破坏条目格式（历史：7-30 多行 title 写坏文件、重复堆积）。
     */
    private String singleLine(String s) {
        if (s == null || s.isBlank()) return "";
        return s.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").strip();
    }

    /**
     * 提取文件头：第一行 # 标题 + 其后到第一个条目（---）之间的手写注释。
     * 防止 save/delete 重建文件时丢弃用户手动添加的说明文字。
     */
    private String extractHeader(String content) {
        int idx = content.indexOf("\n---");
        if (idx <= 0) {
            return content.lines().findFirst().orElse("");
        }
        return content.substring(0, idx).strip();
    }

    private List<Todo> parseEntries(String content) {
        List<Todo> result = new ArrayList<>();
        Matcher matcher = ENTRY_PATTERN.matcher(content);
        while (matcher.find()) {
            try {
                String id = matcher.group(1);
                String title = matcher.group(2).strip();
                String statusStr = matcher.group(4 - 1); // status 为 group(3)
                String dueStr = matcher.group(4);
                String sourceRecordId = matcher.group(5);
                LocalDate createdAt = LocalDate.parse(matcher.group(6));
                LocalDate updatedAt = LocalDate.parse(matcher.group(7));

                TodoStatus status = parseStatus(statusStr);
                LocalDate due = parseDate(dueStr);

                result.add(new Todo(id, title, status, due,
                        sourceRecordId != null && !sourceRecordId.isBlank() ? sourceRecordId.strip() : null,
                        createdAt, updatedAt));
            } catch (Exception e) {
                log.warn("解析待办条目失败: {}", e.getMessage());
            }
        }
        return result;
    }

    /** 两态口径：DONE 保留，其余（含旧 DOING / CANCELLED / 脏值）读作 OPEN。 */
    private TodoStatus parseStatus(String raw) {
        if ("DONE".equals(raw)) return TodoStatus.DONE;
        if (!"OPEN".equals(raw)) {
            log.debug("待办状态非两态，读作 OPEN | status={}", raw);
        }
        return TodoStatus.OPEN;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.strip());
        } catch (Exception e) {
            log.warn("待办到期日解析失败，按无到期处理 | due={}", raw);
            return null;
        }
    }
}
