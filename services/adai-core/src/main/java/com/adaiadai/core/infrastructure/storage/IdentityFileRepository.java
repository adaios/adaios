package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * IdentityFileRepository — 基于文件系统的 Identity 存储实现。
 * <p>
 * 从 {@code data/identity/profile.md} 读取个人档案。
 * profile.md 以 YAML frontmatter 格式存储用户的基础信息、偏好和协作规则。
 */
@Repository
public class IdentityFileRepository implements IdentityRepository {

    private static final Logger log = LoggerFactory.getLogger(IdentityFileRepository.class);

    private static final String PROFILE_PATH = "identity/profile.md";

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\n(.+?)\\n---\\n(.+)", Pattern.DOTALL);

    /**
     * 用于解析 YAML 风格 key-value 的简单模式（不需要引入 SnakeYAML）。
     */
    private static final Pattern KV_PATTERN = Pattern.compile(
            "^(\\w[\\w\\-]*):\\s*(.*)", Pattern.MULTILINE);

    private static final Pattern LIST_PATTERN = Pattern.compile(
            "^\\s*-\\s+(.*)", Pattern.MULTILINE);

    private static final String BODY_TEMPLATE = "# 个人档案\n\n阿呆的个人 AI 协作档案。\n";

    private static final Pattern SUBMAP_PATTERN = Pattern.compile("^\\s*([^:]+):\\s*(.*)", Pattern.MULTILINE);

    private final FileStorage fileStorage;

    public IdentityFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public Optional<IdentityProfile> load() {
        String content = fileStorage.read(PROFILE_PATH);
        if (content == null || content.isBlank()) {
            log.warn("个人档案文件不存在: {}，使用默认档案", PROFILE_PATH);
            return Optional.of(defaultProfile());
        }
        IdentityProfile parsed = parseProfile(content);
        if (parsed == null) {
            log.warn("个人档案解析失败: {}，使用默认档案", PROFILE_PATH);
            return Optional.of(defaultProfile());
        }
        return Optional.of(parsed);
    }

    /**
     * 文件不存在或解析失败时的默认降级档案。
     */
    private IdentityProfile defaultProfile() {
        return new IdentityProfile("阿呆", Map.of(), Map.of(), List.of());
    }

    @Override
    public IdentityProfile save(IdentityProfile profile) {
        String frontmatter = serializeProfile(profile);
        fileStorage.write(PROFILE_PATH, "---\n" + frontmatter + "\n---\n\n" + BODY_TEMPLATE);
        return profile;
    }

    private IdentityProfile parseProfile(String content) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            log.warn("个人档案格式错误（缺少 YAML frontmatter）");
            return null;
        }
        String frontmatter = matcher.group(1);
        String body = matcher.group(2).strip();

        log.debug("parseProfile frontmatter:\n{}", frontmatter);
        Map<String, String> fields = parseFrontmatter(frontmatter);
        log.debug("parseProfile fields: {}", fields);

        String name = fields.getOrDefault("name", "用户");
        Map<String, String> preferences = parseSubMap(fields.get("preferences"));
        Map<String, String> rules = parseSubMap(fields.get("rules"));
        List<String> tags = parseList(fields.get("tags"));

        log.debug("parsed: name={}, prefs={}, rules={}, tags={}", name, preferences, rules, tags);
        return new IdentityProfile(name, preferences, rules, tags);
    }

    /**
     * 逐行解析 YAML frontmatter，识别顶层 key、缩进子 map、缩进 list。
     * 不引入 SnakeYAML，纯文本解析。
     */
    private Map<String, String> parseFrontmatter(String frontmatter) {
        Map<String, String> fields = new LinkedHashMap<>();
        String[] lines = frontmatter.split("\n");
        StringBuilder nested = new StringBuilder();
        String currentKey = null;
        boolean inNested = false;

        for (String line : lines) {
            // 缩进行（以空格或 tab 开头）
            if (line.matches("^[ \t].*")) {
                if (inNested && currentKey != null) {
                    nested.append(line).append('\n');
                }
                continue;
            }
            // 提交上一个嵌套块
            if (inNested && currentKey != null) {
                fields.put(currentKey, nested.toString().strip());
            }

            Matcher m = KV_PATTERN.matcher(line);
            if (m.matches()) {
                String key = m.group(1).trim();
                String value = m.group(2).strip();
                if (value.isEmpty()) {
                    // 后面可能会跟嵌套内容
                    currentKey = key;
                    nested = new StringBuilder();
                    inNested = true;
                } else {
                    fields.put(key, value);
                    currentKey = null;
                    inNested = false;
                }
            }
        }
        // 最后一块
        if (inNested && currentKey != null) {
            fields.put(currentKey, nested.toString().strip());
        }
        return fields;
    }

    private Map<String, String> parseSubMap(String value) {
        if (value == null || value.isBlank()) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        Matcher matcher = SUBMAP_PATTERN.matcher(value);
        while (matcher.find()) {
            String k = matcher.group(1).trim();
            String v = matcher.group(2).strip();
            if (!k.isEmpty() && !k.isBlank()) map.put(k, v);
        }
        return map;
    }

    private List<String> parseList(String value) {
        if (value == null || value.isBlank()) return Collections.emptyList();
        Matcher matcher = LIST_PATTERN.matcher(value);
        List<String> items = new ArrayList<>();
        while (matcher.find()) {
            items.add(matcher.group(1).strip());
        }
        return items;
    }

    private String serializeProfile(IdentityProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(profile.name()).append('\n');

        sb.append("preferences:\n");
        for (var entry : profile.preferences().entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }

        sb.append("rules:\n");
        for (var entry : profile.rules().entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }

        sb.append("tags:\n");
        for (String tag : profile.tags()) {
            sb.append("  - ").append(tag).append('\n');
        }

        return sb.toString();
    }
}
