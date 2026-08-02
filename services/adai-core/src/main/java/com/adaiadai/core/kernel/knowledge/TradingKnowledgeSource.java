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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TradingKnowledgeSource — 交易系统知识源。
 * <p>
 * 读取 {@code os/trading-os/11-context/} 下的五份交付文件：
 * <ul>
 *   <li>identity.md — 系统身份声明</li>
 *   <li>strategy.md — 交易体系结构体</li>
 *   <li>rules.md — 规则调用表 R1-R120</li>
 *   <li>mistakes.md — 高频错误诊断 E1-E30</li>
 *   <li>current.md — 当前交易状态</li>
 * </ul>
 * <p>
 * 使用文件时间戳缓存：每次 {@link #enrich(String)} / {@link #globalContext()} 时检查
 * 文件修改时间，有变化才重读。不做实时文件监听。
 * <p>
 * 这是 {@link KnowledgeSource} 的第一个实现，为后续 Life OS、Project OS 接入提供范式。
 */
@Component
public class TradingKnowledgeSource implements KnowledgeSource {

    private static final Logger log = LoggerFactory.getLogger(TradingKnowledgeSource.class);

    private final Path contextDir;

    private String cachedIdentity;
    private String cachedStrategy;
    private String cachedRules;
    private String cachedMistakes;
    private String cachedCurrent;
    private Instant lastLoadTime;

    public TradingKnowledgeSource(
            @Value("${adai.knowledge.trading-os-path:../../os/trading-os/11-context}") String contextPath) {
        this.contextDir = Paths.get(contextPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        log.info("TradingKnowledgeSource 初始化 | 路径: {}", contextDir);
        loadAll();
    }

    // ── KnowledgeSource 接口 ──

    @Override
    public String name() {
        return "trading";
    }

    @Override
    public String globalContext(String userId) {
        refreshIfChanged();
        return cachedIdentity != null ? cachedIdentity : "";
    }

    @Override
    public String enrich(String userId, String scene) {
        if (!"trading".equals(scene) && !"decision".equals(scene)) {
            // 非交易场景只注入 identity 摘要，让 AI 知道交易系统存在
            return globalContext(userId);
        }

        refreshIfChanged();

        StringBuilder sb = new StringBuilder();
        sb.append("## 交易系统知识\n\n");

        if (cachedIdentity != null && !cachedIdentity.isBlank()) {
            sb.append(cachedIdentity).append("\n\n");
        }
        if (cachedStrategy != null && !cachedStrategy.isBlank()) {
            sb.append(cachedStrategy).append("\n\n");
        }
        if (cachedRules != null && !cachedRules.isBlank()) {
            sb.append(cachedRules).append("\n\n");
        }
        if (cachedMistakes != null && !cachedMistakes.isBlank()) {
            sb.append(cachedMistakes).append("\n\n");
        }
        if (cachedCurrent != null && !cachedCurrent.isBlank()) {
            sb.append(cachedCurrent).append("\n\n");
        }

        return sb.toString();
    }

    // ── 缓存逻辑 ──

    private void refreshIfChanged() {
        if (!Files.isDirectory(contextDir)) {
            if (lastLoadTime == null) {
                log.warn("TradingKnowledge 目录不存在: {}", contextDir);
            }
            return;
        }

        Instant latestMod = latestModTime();
        if (lastLoadTime == null || latestMod.isAfter(lastLoadTime)) {
            loadAll();
        }
    }

    private void loadAll() {
        Map<String, String> files = readAllFiles();
        cachedIdentity = files.getOrDefault("identity.md", "");
        cachedStrategy = files.getOrDefault("strategy.md", "");
        cachedRules = files.getOrDefault("rules.md", "");
        cachedMistakes = files.getOrDefault("mistakes.md", "");
        cachedCurrent = files.getOrDefault("current.md", "");
        lastLoadTime = Instant.now();

        log.info("TradingKnowledge 已加载 | identity={}KB strategy={}KB rules={}KB mistakes={}KB current={}KB",
                kb(cachedIdentity), kb(cachedStrategy), kb(cachedRules),
                kb(cachedMistakes), kb(cachedCurrent));
    }

    private Map<String, String> readAllFiles() {
        String[] fileNames = {"identity.md", "strategy.md", "rules.md", "mistakes.md", "current.md"};
        Map<String, String> result = new LinkedHashMap<>();

        for (String fileName : fileNames) {
            Path file = contextDir.resolve(fileName);
            if (Files.isReadable(file)) {
                try {
                    result.put(fileName, Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("读取 TradingKnowledge 文件失败: {} | {}", fileName, e.getMessage());
                    result.put(fileName, "");
                }
            } else {
                log.debug("TradingKnowledge 文件不存在或不可读: {}", file);
                result.put(fileName, "");
            }
        }
        return result;
    }

    private Instant latestModTime() {
        Instant latest = Instant.EPOCH;
        String[] fileNames = {"identity.md", "strategy.md", "rules.md", "mistakes.md", "current.md"};
        for (String fileName : fileNames) {
            Path file = contextDir.resolve(fileName);
            try {
                if (Files.isReadable(file)) {
                    Instant mod = Files.getLastModifiedTime(file).toInstant();
                    if (mod.isAfter(latest)) latest = mod;
                }
            } catch (IOException ignored) {
            }
        }
        return latest;
    }

    private static String kb(String s) {
        return s == null || s.isBlank() ? "0" : String.valueOf(s.length() / 1024);
    }
}
