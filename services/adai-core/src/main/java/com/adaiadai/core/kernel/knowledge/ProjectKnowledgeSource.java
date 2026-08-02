package com.adaiadai.core.kernel.knowledge;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * ProjectKnowledgeSource — 项目系统知识源。
 * <p>
 * 读取 {@code os/project-os/11-context/identity.md} 和 {@code rules.md}，
 * 注入 AI 上下文。
 * <ul>
 *   <li>globalContext() → identity.md（项目身份声明，~2KB）</li>
 *   <li>enrich("project") → identity.md + rules.md（17 条开发规则）</li>
 * </ul>
 * 与 {@link com.adaiadai.core.domain.project.ProjectContextContributor} 配合：
 * KnowledgeSource 注入项目静态身份和规则知识，Contributor 注入动态 git/rfc/任务。
 */
@Component
public class ProjectKnowledgeSource implements KnowledgeSource {

    private static final Logger log = LoggerFactory.getLogger(ProjectKnowledgeSource.class);

    private final Path contextDir;
    private String cachedIdentity;
    private String cachedRules;
    private Instant lastLoadTime;

    public ProjectKnowledgeSource(
            @Value("${adai.knowledge.project-os-path:../../os/project-os/11-context}") String contextPath) {
        this.contextDir = Paths.get(contextPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        log.info("ProjectKnowledgeSource 初始化 | 路径: {}", contextDir);
        loadAll();
    }

    @Override
    public String name() {
        return "project";
    }

    @Override
    public String globalContext(String userId) {
        refreshIfChanged();
        return cachedIdentity != null ? cachedIdentity : "";
    }

    @Override
    public String enrich(String userId, String scene) {
        if (!"project".equals(scene)) return "";
        refreshIfChanged();
        StringBuilder sb = new StringBuilder();
        if (cachedIdentity != null) sb.append(cachedIdentity).append("\n\n");
        if (cachedRules != null && !cachedRules.isBlank()) sb.append(cachedRules);
        return sb.toString();
    }

    private void refreshIfChanged() {
        Path file = contextDir.resolve("identity.md");
        if (!Files.isReadable(file)) {
            if (lastLoadTime == null) log.debug("Project identity.md 不存在: {}", file);
            return;
        }
        try {
            Instant mod = Files.getLastModifiedTime(file).toInstant();
            if (lastLoadTime == null || mod.isAfter(lastLoadTime)) {
                loadAll();
            }
        } catch (IOException ignored) {
        }
    }

    private void loadAll() {
        Path identityFile = contextDir.resolve("identity.md");
        if (Files.isReadable(identityFile)) {
            try {
                cachedIdentity = Files.readString(identityFile, StandardCharsets.UTF_8);
                log.info("ProjectKnowledge identity 已加载 | {}KB", cachedIdentity.length() / 1024);
            } catch (IOException e) {
                log.warn("读取 Project identity.md 失败: {}", e.getMessage());
                cachedIdentity = "";
            }
        } else {
            cachedIdentity = "";
        }

        Path rulesFile = contextDir.resolve("rules.md");
        if (Files.isReadable(rulesFile)) {
            try {
                cachedRules = Files.readString(rulesFile, StandardCharsets.UTF_8);
                log.info("ProjectKnowledge rules 已加载 | {}KB", cachedRules.length() / 1024);
            } catch (IOException e) {
                log.warn("读取 Project rules.md 失败: {}", e.getMessage());
                cachedRules = "";
            }
        } else {
            cachedRules = "";
        }

        lastLoadTime = Instant.now();
    }
}
