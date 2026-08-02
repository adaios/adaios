package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.infrastructure.storage.TagIndexService.TagSummary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * TagRecommendationService — 标签推荐信号。
 * <p>
 * 从 {@link TagIndexService} 读取标签索引，分析：
 * <ul>
 *   <li><b>Hot</b> — 近 3 天有记录的标签，按频率排序</li>
 *   <li><b>Cold</b> — 超过 14 天没出现、但之前至少记过 2 次的标签（曾经关注过）</li>
 * </ul>
 * 目前被 {@link BriefAppService} 消费，让 AI 在简报中自然提及标签热/冷变化。
 */
@Service
public class TagRecommendationService {

    private final TagIndexService tagIndexService;

    public TagRecommendationService(TagIndexService tagIndexService) {
        this.tagIndexService = tagIndexService;
    }

    /**
     * 计算标签推荐信号。
     */
    public TagRecommendations getRecommendations(String userId) {
        List<TagSummary> allTags = tagIndexService.getAllTags(userId);
        LocalDateTime now = LocalDateTime.now();

        // Hot: 近 3 天出现过，按出现次数降序
        List<String> hot = allTags.stream()
                .filter(t -> t.lastAt().isAfter(now.minusDays(3)))
                .sorted(Comparator.comparingInt(TagSummary::count).reversed())
                .map(TagSummary::name)
                .limit(5)
                .toList();

        // Cold: 超过 14 天没出现，且之前至少记过 2 次
        List<String> cold = allTags.stream()
                .filter(t -> t.lastAt().isBefore(now.minusDays(14)))
                .filter(t -> t.count() >= 2)
                .sorted(Comparator.comparingInt(TagSummary::count).reversed())
                .map(TagSummary::name)
                .limit(3)
                .toList();

        return new TagRecommendations(hot, cold);
    }

    /**
     * 标签推荐结果。
     */
    public record TagRecommendations(
            List<String> hot,   // 近期高频标签
            List<String> cold   // 曾经关注、最近冷落的标签
    ) {}
}
