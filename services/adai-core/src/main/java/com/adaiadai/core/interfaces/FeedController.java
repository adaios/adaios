package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.FeedAppService;
import com.adaiadai.core.application.FeedAppService.FeedResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * FeedController — 今日 Feed 流 API。
 * <p>
 * 只返回今天的数据，分页查询。
 * 历史数据走时间线入口（TopBar 日期点击）。
 */
@RestController
@RequestMapping("/api/v1/feed")
public class FeedController {

    private final FeedAppService feedAppService;

    public FeedController(FeedAppService feedAppService) {
        this.feedAppService = feedAppService;
    }

    @GetMapping
    public ResponseEntity<FeedResponse> getFeed(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        LocalDate queryDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        return ResponseEntity.ok(feedAppService.getFeed(userId, queryDate, page, size));
    }
}
