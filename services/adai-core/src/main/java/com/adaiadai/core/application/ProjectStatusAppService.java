package com.adaiadai.core.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ProjectStatusAppService — 项目状态汇总服务。
 * <p>
 * 纯数据聚合，不调用 AI。汇总 git log、RFC 列表、Kernel 组件、
 * Domain OS 状态等，供 Project OS 页面展示。
 */
@Service
public class ProjectStatusAppService {

    private static final Logger log = LoggerFactory.getLogger(ProjectStatusAppService.class);

    private static final Pattern RFC_FRONTMATTER = Pattern.compile(
            "^---\\n" +
                    "title:\\s*(.+)\\n" +
                    "date:\\s*(\\S+)\\n" +
                    "status:\\s*(\\S+).*\\n" +
                    "(?:.*\\n)*?" +
                    "---",
            Pattern.MULTILINE);

    private final Path projectRoot;

    public ProjectStatusAppService(
            @Value("${adai.project.root-path:}") String configuredPath) {
        this.projectRoot = resolveRoot(configuredPath);
    }

    /**
     * 获取项目状态摘要。
     */
    public StatusResult getStatus() {
        List<RfcItem> rfcItems = parseRfcFrontmatter();
        return new StatusResult(
                "AdaiOS",
                "modular-monolith",
                kernelComponents(),
                domainStatus(),
                rfcItems,
                countCommits(),
                countApiEndpoints()
        );
    }

    // ── 各维度查询 ──

    private Map<String, String> kernelComponents() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("identity", "done");
        map.put("record", "done");
        map.put("timeline", "done");
        map.put("context", "done");
        map.put("memory", "done");
        map.put("knowledge", "done");
        return map;
    }

    private Map<String, String> domainStatus() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("trading", "complete");
        map.put("life", "skeleton");
        map.put("project", "skeleton");
        return map;
    }

    /**
     * 解析 docs/rfc/ 下所有 RFC 的 YAML frontmatter。
     * 返回按日期倒序排列的 RFC 状态列表。
     */
    private List<RfcItem> parseRfcFrontmatter() {
        List<RfcItem> items = new ArrayList<>();
        try {
            Path rfcDir = projectRoot.resolve("docs/rfc");
            if (!Files.isDirectory(rfcDir)) return items;

            try (var stream = Files.list(rfcDir)) {
                for (Path p : stream.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().endsWith(".md"))
                        .sorted(Comparator.reverseOrder())
                        .toList()) {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    Matcher matcher = RFC_FRONTMATTER.matcher(content);
                    if (matcher.find()) {
                        String title = matcher.group(1).strip();
                        String date = matcher.group(2).strip();
                        String status = matcher.group(3).strip();
                        items.add(new RfcItem(title, date, status));
                    } else {
                        // 无 frontmatter 的文件用文件名作为标题
                        String name = p.getFileName().toString().replace(".md", "");
                        items.add(new RfcItem(name, name.substring(0, 8), "unknown"));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("RFC frontmatter 解析失败: {}", e.getMessage());
        }
        return items;
    }

    private int countCommits() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-list", "--count", "HEAD");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                process.waitFor();
                return line != null ? Integer.parseInt(line.trim()) : 0;
            }
        } catch (Exception e) {
            log.debug("Git commit 计数失败: {}", e.getMessage());
            return 0;
        }
    }

    private Integer countApiEndpoints() {
        // REVIEW #228 统一口径：单一来源 = Gradle 生成的 META-INF/endpoints.txt。
        // build.gradle.kts 的 processResources 依赖 generateEndpointsFile（bootJar/bootRun 均触发），
        // 生产 jar-only 与开发环境读到同一份生成数据，dev/生产数字必然一致。
        // 已移除旧 dev 回退扫源码（双实现口径不同：Java 侧 split 会数到注释/字符串里的注解名）。
        Integer fromResource = countEndpointsFromResource();
        if (fromResource != null) {
            return fromResource;
        }
        // REVIEW #247：返回 null 而非 0——前端据此显示「未知」，不与「真 0 个端点」混淆。
        log.warn("API 端点计数资源缺失（META-INF/endpoints.txt），请确认 processResources 已执行");
        return null;
    }

    /** 读 classpath 资源 {@code META-INF/endpoints.txt}（Gradle 生成，唯一口径来源）。 */
    private Integer countEndpointsFromResource() {
        try (var in = getClass().getClassLoader().getResourceAsStream("META-INF/endpoints.txt")) {
            if (in == null) return null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return line != null ? Integer.parseInt(line.trim()) : 0;
            }
        } catch (Exception e) {
            log.debug("API 端点资源读取失败: {}", e.getMessage());
            return null;
        }
    }

    // ── 根目录解析（与 ProjectContextContributor 逻辑一致）──

    private Path resolveRoot(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path p = Paths.get(configuredPath).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
        }
        Path current = Paths.get(".").toAbsolutePath().normalize();
        // 向上查找 monorepo 根目录：同时有 CLAUDE.md 和 docs/rfc/ 才是真正的项目根
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("CLAUDE.md"))
                    && Files.isDirectory(dir.resolve("docs/rfc"))) {
                return dir;
            }
        }
        if (current.endsWith("adai-core")) {
            return current.getParent().getParent();
        }
        return current;
    }

    // ── DTO ──

    public record StatusResult(
            String project,
            String architecture,
            Map<String, String> kernelComponents,
            Map<String, String> domainStatus,
            List<RfcItem> rfcItems,
            int commitCount,
            // REVIEW #247：Integer 可空——endpoints.txt 资源缺失时返回 null
            // 而非 0（0 与「未知」语义混淆），前端可显示「未知」。
            Integer apiEndpoints
    ) {}

    public record RfcItem(
            String title,
            String date,
            String status
    ) {}
}
