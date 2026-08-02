package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TimelineAppService;
import com.adaiadai.core.kernel.timeline.TimelineEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TimelineController — 时间线查询 REST API。
 */
@RestController
@RequestMapping("/api/v1/timeline")
public class TimelineController {

    private final TimelineAppService timelineAppService;

    public TimelineController(TimelineAppService timelineAppService) {
        this.timelineAppService = timelineAppService;
    }

    @GetMapping
    public ResponseEntity<List<TimelineEntry>> getTimeline(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "50") int limit) {

        List<TimelineEntry> entries;
        if (type != null && !type.isBlank()) {
            entries = timelineAppService.getTimelineByType(userId, type);
        } else {
            entries = timelineAppService.getRecent(userId, limit);
        }
        return ResponseEntity.ok(entries);
    }
}
