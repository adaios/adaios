package com.adaiadai.core.application;

import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * DomainActivityService — 按 domain 统计活跃度信号。
 * <p>
 * 为 Layer 2（主动推送）提供数据信号：每个 domain 今日/本周记录数、趋势方向。
 * 目前被 {@link BriefAppService} 消费，让 AI 在简报中自然提及 domain 活跃/冷清。
 */
@Service
public class DomainActivityService {

    private final RecordRepository recordRepository;

    public DomainActivityService(RecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    /**
     * 计算所有 domain 的活跃度信号。
     */
    public DomainBriefActivity getActivity() {
        List<ContentRecord> allRecords = recordRepository.findAll();
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(7);
        LocalDate prevWeekEnd = weekAgo;
        LocalDate prevWeekStart = weekAgo.minusDays(7);

        Map<String, Integer> todayCounts = new HashMap<>();
        Map<String, Integer> weekCounts = new HashMap<>();
        Map<String, Integer> prevWeekCounts = new HashMap<>();

        for (ContentRecord r : allRecords) {
            LocalDate d = r.createdAt().toLocalDate();
            String domain = r.domain() != null ? r.domain() : "life";

            if (d.equals(today)) {
                todayCounts.merge(domain, 1, Integer::sum);
            }
            if (!d.isBefore(weekAgo) && !d.isAfter(today)) {
                weekCounts.merge(domain, 1, Integer::sum);
            }
            if (!d.isBefore(prevWeekStart) && !d.isAfter(prevWeekEnd)) {
                prevWeekCounts.merge(domain, 1, Integer::sum);
            }
        }

        List<DomainActivityItem> items = new ArrayList<>();
        // 确保三个主要 domain 都有条目
        for (String domain : new String[]{"life", "trading", "project"}) {
            int todayCount = todayCounts.getOrDefault(domain, 0);
            int weekCount = weekCounts.getOrDefault(domain, 0);
            int prevCount = prevWeekCounts.getOrDefault(domain, 0);

            String trend;
            if (weekCount == 0) {
                trend = "inactive";
            } else if (prevCount == 0) {
                trend = "up";
            } else if (weekCount > prevCount * 1.5) {
                trend = "up";
            } else if (weekCount < prevCount * 0.5) {
                trend = "quiet";
            } else {
                trend = "stable";
            }

            items.add(new DomainActivityItem(domain, todayCount, weekCount, trend));
        }

        return new DomainBriefActivity(items);
    }

    /**
     * 单个 domain 的活跃度指标。
     */
    public record DomainActivityItem(
            String domain,
            int todayCount,
            int weekCount,
            String trend   // "up" | "quiet" | "stable" | "inactive"
    ) {}

    /**
     * 所有 domain 的活跃度汇总。
     */
    public record DomainBriefActivity(List<DomainActivityItem> domains) {}
}
