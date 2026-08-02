package com.adaiadai.core.application;

import com.adaiadai.core.kernel.timeline.TimelineEntry;
import com.adaiadai.core.kernel.timeline.TimelineProjection;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TimelineAppService — 时间线查询应用服务。
 * <p>
 * 提供面向用户的时间线查询接口，通过 TimelineProjection 获取数据。
 */
@Service
public class TimelineAppService {

    private final TimelineProjection timelineProjection;

    public TimelineAppService(TimelineProjection timelineProjection) {
        this.timelineProjection = timelineProjection;
    }

    public List<TimelineEntry> getFullTimeline(String userId) {
        return timelineProjection.fullTimeline(userId);
    }

    public List<TimelineEntry> getTimelineByType(String userId, String type) {
        return timelineProjection.timelineByType(userId, type);
    }

    public List<TimelineEntry> getRecent(String userId, int limit) {
        return timelineProjection.recent(userId, limit);
    }
}
