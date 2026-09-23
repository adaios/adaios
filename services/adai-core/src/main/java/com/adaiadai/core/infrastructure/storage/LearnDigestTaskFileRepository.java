package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnDigestTask;
import com.adaiadai.core.domain.learn.LearnDigestTaskRepository;
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
 * LearnDigestTaskFileRepository — 「整理任务」追踪记录落盘（2026-09-23 分享追踪批）。
 *
 * <p>落 {@code data/{userId}/learn/_digest-tasks.json}（File First，与 {@code _quota.json} 并列）：
 * <pre>{"tasks": [{"id": "dtask_…", "url": "https://mp.weixin.qq.com/s/…", "status": "done",
 *                 "title": "央行2025Q4货币政策报告要点", "submittedAt": "2026-09-23T23:06:05", …}]}</pre>
 *
 * <p><b>为什么不落在 learn/{type}/ 主题目录</b>：那里的 {@code .md} 是交付物（卡片）。
 * 这是过程账本，放在 {@code learn/} 根下与 {@code _quota.json} 同级——卡片扫描只认
 * {@code learn/{ai|trading|other}/**}，不会把它当卡片读出来。
 *
 * <p><b>为什么 fail-open</b>：见 {@link LearnDigestTaskRepository#save} 的说明——写不进去最多是
 * 「这条查不到踪迹」，绝不能反过来把已经成功的整理报成失败（那比没有追踪更糟）。
 *
 * <p>滚动窗口只留最近 {@value #MAX_KEPT} 条；读-改-写在同一把 per-user 条带锁内完成
 * （pitfall「整文件重写并发」防复发）。
 */
@Repository
public class LearnDigestTaskFileRepository implements LearnDigestTaskRepository {

    private static final Logger log = LoggerFactory.getLogger(LearnDigestTaskFileRepository.class);
    private static final ObjectMapper MAPPER = StrictJson.strict(new ObjectMapper());
    private static final String TASKS_PATH = "learn/_digest-tasks.json";
    private static final String FIELD_TASKS = "tasks";
    private static final int DEFAULT_LIMIT = 20;
    /** 滚动窗口：最近这么多条（一天分享几十条也够回头看了，避免文件无限增长）。 */
    private static final int MAX_KEPT = 50;
    private static final int LOCK_STRIPES = 16;

    private final Object[] locks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private final com.adaiadai.core.kernel.storage.FileStorage fileStorage;

    public LearnDigestTaskFileRepository(com.adaiadai.core.kernel.storage.FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    private Object lockFor(String userId) {
        int h = (userId != null ? userId : "default").hashCode();
        return locks[(h ^ (h >>> 16)) & (locks.length - 1)];
    }

    @Override
    public void save(String userId, LearnDigestTask task) {
        if (task == null || task.id() == null || task.id().isBlank()) return;
        synchronized (lockFor(userId)) {
            JsonNode root = readRootQuietly(userId);
            ArrayNode tasks = MAPPER.createArrayNode();
            JsonNode previous = root == null ? null : root.path(FIELD_TASKS);
            if (previous != null && previous.isArray()) {
                for (JsonNode n : previous) {
                    // 同 id 覆盖：状态推进时反复写，不留重复行
                    if (!task.id().equals(n.path("id").asText())) tasks.add(n);
                }
            }
            tasks.insert(0, toNode(task));
            while (tasks.size() > MAX_KEPT) tasks.remove(tasks.size() - 1);

            ObjectNode next = MAPPER.createObjectNode();
            next.set(FIELD_TASKS, tasks);
            try {
                fileStorage.write(userId, TASKS_PATH,
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(next));
            } catch (Exception e) {
                // fail-open：追踪记录写不进去，绝不把用户这次整理判成失败（卡片可能已经落盘了）
                log.warn("learn 整理任务记录写入失败（不影响整理本身）| userId={} | id={} | {}",
                        userId, task.id(), e.getMessage());
            }
        }
    }

    @Override
    public List<LearnDigestTask> findRecent(String userId, int limit) {
        int take = limit > 0 ? limit : DEFAULT_LIMIT;
        synchronized (lockFor(userId)) {
            JsonNode root = readRootQuietly(userId);
            List<LearnDigestTask> out = new ArrayList<>();
            JsonNode tasks = root == null ? null : root.path(FIELD_TASKS);
            if (tasks != null && tasks.isArray()) {
                for (JsonNode n : tasks) {
                    if (out.size() >= take) break;
                    try {
                        out.add(fromNode(n));
                    } catch (Exception e) {
                        // 单条坏掉不该让整个清单打不开（跳过它，保留其余）
                        log.warn("learn 整理任务记录单条解析失败，已跳过 | userId={} | {}", userId, e.getMessage());
                    }
                }
            }
            return out;
        }
    }

    // ── 读写（手写字段映射，与项目其它文件仓储同风格：字段改名不会静默错位） ──

    private static ObjectNode toNode(LearnDigestTask t) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", t.id());
        n.put("url", t.url());
        n.put("sourceTitle", t.sourceTitle());
        n.put("platform", t.platform());
        n.put("status", t.status());
        n.put("stage", t.stage());
        n.put("message", t.message());
        n.put("type", t.type());
        n.put("title", t.title());
        n.put("topic", t.topic());
        n.put("submittedAt", t.submittedAt());
        n.put("settledAt", t.settledAt());
        return n;
    }

    private static LearnDigestTask fromNode(JsonNode n) {
        return new LearnDigestTask(
                text(n, "id"), text(n, "url"), text(n, "sourceTitle"), text(n, "platform"),
                text(n, "status"), text(n, "stage"), text(n, "message"),
                text(n, "type"), text(n, "title"), text(n, "topic"),
                text(n, "submittedAt"), text(n, "settledAt"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /** 读记录文件：不存在 = 全新用户（返回 null）；读/解析失败 = 记 WARN 后按空处理（fail-open）。 */
    private JsonNode readRootQuietly(String userId) {
        String content;
        try {
            content = fileStorage.read(userId, TASKS_PATH);
        } catch (Exception e) {
            log.warn("learn 整理任务记录读不出来，按暂无记录处理 | userId={} | {}", userId, e.getMessage());
            return null;
        }
        if (content == null || content.isBlank()) return null;
        try {
            return MAPPER.readTree(content);
        } catch (Exception e) {
            log.warn("learn 整理任务记录内容异常，按暂无记录处理 | userId={} | {}", userId, e.getMessage());
            return null;
        }
    }
}
