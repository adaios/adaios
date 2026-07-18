package com.adaiadai.core.kernel.timeline;

import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TimelineProjection — Record 的时间序列投影。
 * <p>
 * 将 Record 按时间维度组织为时间线。
 * Timeline 不是独立实体，而是 Record 的查询投影，自动从 Record 文件生成。
 */
@Component
public class TimelineProjection {

    private final RecordRepository recordRepository;

    public TimelineProjection(RecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    /**
     * 获取完整时间线（按时间倒序）。
     *
     * @return 时间线条目列表
     */
    public List<TimelineEntry> fullTimeline() {
        return recordRepository.findAll().stream()
                .map(this::toEntry)
                .sorted((a, b) -> b.dateTime().compareTo(a.dateTime()))
                .toList();
    }

    /**
     * 获取指定类型的时间线。
     *
     * @param type 记录类型
     * @return 过滤后的时间线
     */
    public List<TimelineEntry> timelineByType(String type) {
        return fullTimeline().stream()
                .filter(e -> type.equals(e.type()))
                .toList();
    }

    /**
     * 获取最近 N 条时间线条目。
     *
     * @param limit 数量上限
     * @return 最近的 N 条
     */
    public List<TimelineEntry> recent(int limit) {
        return fullTimeline().stream()
                .limit(limit)
                .toList();
    }

    private TimelineEntry toEntry(ContentRecord record) {
        return new TimelineEntry(
                record.id(),
                record.type(),
                record.title(),
                record.tags(),
                record.createdAt()
        );
    }
}
