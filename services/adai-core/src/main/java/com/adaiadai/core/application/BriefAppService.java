package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * BriefAppService — AI 简报服务。
 * <p>
 * 每天第一次打开 App 时，AI 综合近期记录、记忆、持仓生成一段自然语言问候。
 */
@Service
public class BriefAppService {

    private static final Logger log = LoggerFactory.getLogger(BriefAppService.class);

    private final IdentityRepository identityRepository;
    private final RecordRepository recordRepository;
    private final MemoryService memoryService;
    private final AiClient aiClient;

    public BriefAppService(IdentityRepository identityRepository,
                           RecordRepository recordRepository,
                           MemoryService memoryService,
                           AiClient aiClient) {
        this.identityRepository = identityRepository;
        this.recordRepository = recordRepository;
        this.memoryService = memoryService;
        this.aiClient = aiClient;
    }

    /**
     * 生成今日简报。
     */
    public String generateBrief() {
        // 收集今日简报所需数据
        List<ContentRecord> recentRecords = recordRepository.findAll().stream()
                .filter(r -> r.createdAt().toLocalDate().isAfter(LocalDate.now().minusDays(2)))
                .toList();
        List<Memory> recentMemories = memoryService.recent(2);
        String identityName = identityRepository.load()
                .map(IdentityProfile::name)
                .orElse("用户");

        String prompt = buildBriefPrompt(identityName, recentRecords, recentMemories);

        // 用 Mock AI 时返回固定格式，用 DeepSeek 时真实生成
        try {
            // 构造一个简易 ContextPackage 风格的 prompt
            AiUnderstanding understanding = aiClient.understand(
                    new com.adaiadai.core.kernel.context.engine.ContextPackage(
                            "brief", "",
                            "今日简报", prompt, List.of(),
                            List.of(), prompt, java.time.LocalDateTime.now()
                    ));
            return understanding.summary();
        } catch (Exception e) {
            log.warn("AI 简报生成失败，返回默认简报: {}", e.getMessage());
            return defaultBrief(identityName, recentRecords, recentMemories);
        }
    }

    private String buildBriefPrompt(String name, List<ContentRecord> records, List<Memory> memories) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个个人 AI 助手。请根据以下信息，生成一段简短、温暖的今日问候（不超过200字）。\n\n");
        sb.append("用户称呼：").append(name).append("\n\n");

        if (!records.isEmpty()) {
            sb.append("近期记录：\n");
            for (ContentRecord r : records) {
                sb.append("- [").append(r.createdAt().toLocalDate()).append("] ")
                        .append(r.content()).append("\n");
            }
            sb.append("\n");
        }

        if (!memories.isEmpty()) {
            sb.append("AI 近期理解：\n");
            for (Memory m : memories) {
                sb.append("- ").append(m.summary()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("""
                要求：
                1. 用自然语言，像朋友一样问候
                2. 提及 1-2 件最近发生的具体事情
                3. 给出 1 句简单提醒或建议
                4. 语气温暖、简洁
                """);
        return sb.toString();
    }

    private String defaultBrief(String name, List<ContentRecord> records, List<Memory> memories) {
        int todayCount = (int) records.stream()
                .filter(r -> r.createdAt().toLocalDate().equals(LocalDate.now()))
                .count();
        String recordSummary = todayCount > 0
                ? "今天已有 " + todayCount + " 条记录"
                : "今天还没有记录";

        return "早上好，" + name + " ☀️\n\n"
                + recordSummary + "。"
                + (!records.isEmpty() ? "\n昨天有 " + records.size() + " 条动态。" : "")
                + "\n有什么想记下来的吗？";
    }
}
