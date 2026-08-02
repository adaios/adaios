package com.adaiadai.core.domain.project;

import com.adaiadai.core.kernel.context.engine.ContextContributor;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ProjectContextContributor — 项目场景上下文贡献者。
 * <p>
 * 为 project 场景注入：
 * <ul>
 *   <li>最近 5 个 git commit 摘要</li>
 *   <li>docs 目录结构索引（RFC + 架构文档）</li>
 *   <li>RFC 状态列表（从 YAML frontmatter 解析）</li>
 *   <li>任务统计和当前进行中的任务</li>
 * </ul>
 */
@Component
public class ProjectContextContributor implements ContextContributor {

    private static final Logger log = LoggerFactory.getLogger(ProjectContextContributor.class);

    private static final Pattern RFC_FRONTMATTER = Pattern.compile(
            "^---\\n" +
                    "title:\\s*(.+)\\n" +
                    "date:\\s*(\\S+)\\n" +
                    "status:\\s*(\\S+).*\\n" +
                    "(?:.*\\n)*?" +
                    "---",
            Pattern.MULTILINE);

    private final Path projectRoot;
    private final TaskRepository taskRepository;

    private String cachedGitLog;
    private Instant gitLogCacheTime;

    public ProjectContextContributor(
            @Value("${adai.project.root-path:}") String configuredPath,
            TaskRepository taskRepository) {
        this.projectRoot = resolveProjectRoot(configuredPath);
        this.taskRepository = taskRepository;
        log.info("ProjectContextContributor 初始化 | 项目根目录: {}", projectRoot);
    }

    private Path resolveProjectRoot(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path p = Paths.get(configuredPath).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
            log.warn("配置的 adai.project.root-path 不存在: {}", p);
        }
        Path current = Paths.get(".").toAbsolutePath().normalize();
        // 向上查找 monorepo 根目录：同时有 CLAUDE.md 和 docs/rfc/ 才是真正的项目根
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("CLAUDE.md"))
                    && Files.isDirectory(dir.resolve("docs/rfc"))) {
                return dir;
            }
        }
        Path fallback = current;
        if (fallback.endsWith("adai-core")) {
            fallback = fallback.getParent().getParent();
        }
        return fallback;
    }

    @Override
    public boolean supports(String scene) {
        return "project".equals(scene);
    }

    @Override
    public boolean isDefault() {
        return false;
    }

    @Override
    public String enrich(String userId, String identityRef, ContentRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 项目当前状态\n\n");
        sb.append(loadGitSummary());
        sb.append(loadDocsIndex());
        sb.append(loadRfcStatus());
        sb.append(loadTaskSummary(userId));
        return sb.toString();
    }

    @Override
    public String globalContext(String userId) {
        return loadGitSummary();
    }

    // ── Git 摘要 ──

    private String loadGitSummary() {
        if (cachedGitLog != null && gitLogCacheTime != null
                && Instant.now().minusSeconds(60).isBefore(gitLogCacheTime)) {
            return cachedGitLog;
        }
        try {
            List<String> lines = runGitLog();
            if (lines.isEmpty()) return cachedGitLog != null ? cachedGitLog : "";

            StringBuilder sb = new StringBuilder();
            sb.append("**AdaiOS 最近开发活动：**\n");
            for (String line : lines) {
                sb.append("- ").append(line).append("\n");
            }
            cachedGitLog = sb.toString();
            gitLogCacheTime = Instant.now();
            return cachedGitLog;
        } catch (Exception e) {
            log.debug("Git log 读取失败: {}", e.getMessage());
            return cachedGitLog != null ? cachedGitLog : "";
        }
    }

    private List<String> runGitLog() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("git", "log", "--oneline",
                "--format=%h %s (%cd)", "--date=format:%m-%d %H:%M", "-5");
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().collect(Collectors.toList());
            process.waitFor();
            return lines;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    // ── 文档索引 ──

    private String loadDocsIndex() {
        try {
            Path docsDir = projectRoot.resolve("docs");
            if (!Files.isDirectory(docsDir)) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("\n**项目文档索引：**\n");

            Path rfcDir = docsDir.resolve("rfc");
            if (Files.isDirectory(rfcDir)) {
                List<String> rfcs = new ArrayList<>();
                try (var stream = Files.list(rfcDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .limit(5)
                            .forEach(p -> rfcs.add(p.getFileName().toString().replace(".md", "")));
                }
                if (!rfcs.isEmpty()) {
                    sb.append("RFC: ").append(String.join(", ", rfcs)).append("\n");
                }
            }

            Path archDir = docsDir.resolve("architecture");
            if (Files.isDirectory(archDir)) {
                List<String> archDocs = new ArrayList<>();
                try (var stream = Files.list(archDir)) {
                    stream.filter(Files::isRegularFile)
                            .map(p -> p.getFileName().toString().replace(".md", ""))
                            .sorted()
                            .forEach(archDocs::add);
                }
                if (!archDocs.isEmpty()) {
                    sb.append("架构文档(").append(archDocs.size()).append("份): ");
                    sb.append(String.join(", ",
                            archDocs.stream().limit(8).collect(Collectors.toList())));
                    if (archDocs.size() > 8) sb.append(" ...");
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("文档索引生成失败: {}", e.getMessage());
            return "";
        }
    }

    // ── RFC 状态 ──

    private String loadRfcStatus() {
        try {
            Path rfcDir = projectRoot.resolve("docs/rfc");
            if (!Files.isDirectory(rfcDir)) return "";

            List<String> lines = new ArrayList<>();
            try (var stream = Files.list(rfcDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().endsWith(".md"))
                        .sorted(Comparator.reverseOrder())
                        .limit(12)
                        .forEach(p -> {
                            try {
                                String content = Files.readString(p, StandardCharsets.UTF_8);
                                Matcher matcher = RFC_FRONTMATTER.matcher(content);
                                if (matcher.find()) {
                                    String title = matcher.group(1).strip();
                                    String status = matcher.group(3).strip();
                                    lines.add("- [" + status + "] " + title);
                                }
                            } catch (Exception e) {
                                // skip
                            }
                        });
            }
            if (lines.isEmpty()) return "";
            return "\n**RFC 状态：**\n" + String.join("\n", lines) + "\n";
        } catch (Exception e) {
            log.debug("RFC 状态加载失败: {}", e.getMessage());
            return "";
        }
    }

    // ── 任务摘要 ──

    private String loadTaskSummary(String userId) {
        try {
            var stats = taskRepository.stats(userId);
            List<Task> doingTasks = taskRepository.findAll(TaskStatus.DOING, null, userId);

            StringBuilder sb = new StringBuilder();
            sb.append("\n**任务状态：** ")
                    .append("总计").append(stats.total())
                    .append(" | 待办").append(stats.todo())
                    .append(" | 进行").append(stats.doing())
                    .append(" | 完成").append(stats.done())
                    .append("\n");
            if (!doingTasks.isEmpty()) {
                sb.append("**当前进行中：**\n");
                for (Task t : doingTasks) {
                    sb.append("- ").append(t.title());
                    if (t.priority() != null && !t.priority().equals("P2")) {
                        sb.append(" (").append(t.priority()).append(")");
                    }
                    sb.append("\n");
                }
            }

            // 最近 7 天完成的任务
            LocalDate weekAgo = LocalDate.now().minusDays(7);
            List<Task> recentDone = taskRepository.findAll(userId).stream()
                    .filter(t -> t.status() == TaskStatus.DONE)
                    .filter(t -> t.updatedAt() != null && !t.updatedAt().isBefore(weekAgo))
                    .sorted(Comparator.comparing(Task::updatedAt).reversed())
                    .limit(10)
                    .toList();
            if (!recentDone.isEmpty()) {
                sb.append("**最近 7 天完成：**\n");
                for (Task t : recentDone) {
                    sb.append("- ").append(t.title()).append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.debug("任务摘要加载失败: {}", e.getMessage());
            return "";
        }
    }
}
