package com.adaiadai.core.kernel.context.engine;

import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ContextEngine — 上下文引擎，Kernel 核心能力。
 * <p>
 * 职责：组合 Identity + 今日会话历史 + Record + Domain OS 贡献 → ContextPackage。
 * <p>
 * 今日会话：同一天内用户的所有记录自动构成会话上下文，
 * 当前记录能看到之前的 5 条记录，实现"你怎么看这个操作"的关联能力。
 */
@Component
public class ContextEngine {

    private static final Logger log = LoggerFactory.getLogger(ContextEngine.class);

    private static final int SESSION_HISTORY_SIZE = 5;

    private final IdentityRepository identityRepository;
    private final RecordRepository recordRepository;
    private final List<ContextContributor> contributors;

    public ContextEngine(IdentityRepository identityRepository,
                         RecordRepository recordRepository,
                         List<ContextContributor> contributors) {
        this.identityRepository = identityRepository;
        this.recordRepository = recordRepository;
        this.contributors = contributors;
    }

    /**
     * 为指定记录组装上下文包。
     */
    public ContextPackage compose(String scene, String recordId) {
        ContentRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: " + recordId));
        return compose(scene, record);
    }

    /**
     * 为指定的 Record 组装上下文包（含今日会话历史 + 领域上下文）。
     */
    public ContextPackage compose(String scene, ContentRecord record) {
        String identityRef = loadIdentitySummary();
        String sessionHistory = loadSessionHistory(record);
        String domainContext = enrichFromContributors(scene, identityRef, record);
        String prompt = buildPrompt(scene, identityRef, record, sessionHistory, domainContext);

        log.info("ContextPackage 组装完成 | scene={} | record={} | 会话历史={}条 | 预估 tokens={}",
                scene, record.id(),
                sessionHistory.isBlank() ? 0 : sessionHistory.split("\n").length,
                (prompt.length() / 2));

        return new ContextPackage(
                scene, identityRef,
                record.title(), record.content(), record.tags(),
                List.of(sessionHistory),
                prompt,
                java.time.LocalDateTime.now());
    }

    // ── 内部方法 ──

    private String loadIdentitySummary() {
        Optional<IdentityProfile> profile = identityRepository.load();
        return profile.map(p -> """
                用户身份摘要：
                - 称呼：%s
                - 偏好：%s
                - 协作规则：%s
                """.formatted(
                        p.name(),
                        String.join("; ", p.preferences().values()),
                        String.join("; ", p.rules().values())
                )).orElse("用户身份：未配置");
    }

    /**
     * 加载今日会话历史（当前记录之前的最近 5 条同一天记录）。
     */
    private String loadSessionHistory(ContentRecord currentRecord) {
        LocalDate today = currentRecord.createdAt().toLocalDate();
        List<ContentRecord> allRecords = recordRepository.findAll();

        List<ContentRecord> todayRecords = allRecords.stream()
                .filter(r -> r.createdAt().toLocalDate().equals(today))
                .filter(r -> !r.id().equals(currentRecord.id())) // 排除当前记录
                .limit(SESSION_HISTORY_SIZE)
                .collect(Collectors.toList());

        if (todayRecords.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("## 今日会话历史\n\n");
        List<ContentRecord> reversed = new ArrayList<>(todayRecords);
        java.util.Collections.reverse(reversed);
        for (ContentRecord r : reversed) {
            String time = r.createdAt().toLocalTime()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            sb.append("- [").append(time).append("] ").append(r.content()).append("\n");
        }
        return sb.toString();
    }

    private String enrichFromContributors(String scene, String identityRef, ContentRecord record) {
        for (ContextContributor contributor : contributors) {
            if (!contributor.isDefault() && contributor.supports(scene)) {
                String context = contributor.enrich(identityRef, record);
                if (context != null && !context.isBlank()) {
                    log.info("使用场景贡献者: {} | scene={}", contributor.getClass().getSimpleName(), scene);
                    return context;
                }
            }
        }
        return "";
    }

    private String buildPrompt(String scene, String identityRef, ContentRecord record,
                               String sessionHistory, String domainContext) {
        String prompt = """
                你是一个个人 AI 助手，正在处理用户的一条新记录。

                %s

                场景：%s
                """.formatted(identityRef, scene);

        // 插入今日会话历史
        if (!sessionHistory.isBlank()) {
            prompt += "\n" + sessionHistory + "\n";
        }

        // 当前记录
        prompt += """
                当前记录：
                ---
                标题：%s
                标签：%s
                内容：
                %s
                ---
                """.formatted(
                record.title(),
                String.join(", ", record.tags()),
                record.content()
        );

        // 领域上下文
        if (domainContext != null && !domainContext.isBlank()) {
            prompt += "\n当前领域上下文：\n" + domainContext + "\n";
        }

        // 场景感知 Prompt
        if ("question".equals(scene)) {
            prompt += """

                请回答用户的问题，同时输出 JSON 格式（不要包裹 markdown 代码块）：
                {
                  "summary": "你的回答",
                  "tags": ["标签1", "标签2"],
                  "sentiment": "neutral",
                  "actionable": false,
                  "actionSuggestion": null
                }
                """;
        } else {
            prompt += """

                请分析这条记录，输出 JSON 格式（不要包裹 markdown 代码块）：
                {
                  "summary": "一句话摘要",
                  "tags": ["标签1", "标签2", "标签3"],
                  "sentiment": "positive 或 negative 或 neutral",
                  "actionable": true 或 false,
                  "actionSuggestion": "如果需要后续操作，写建议；否则写 null"
                }
                """;
        }

        return prompt;
    }
}
