package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.FeedAppService;
import com.adaiadai.core.application.FeedAppService.Feed;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * FeedController — Feed 流 API。
 * <p>
 * App 只需调这一个接口就能渲染整个页面：
 * 简报（brief）+ 记录（record）+ AI 理解（ai_note）按时间合并。
 * <p>
 * 支持会话模式：传入 {@code since} 时间戳，只返回该时间之后的条目，
 * 更早的条目计数在 {@code earlierCount} 中。
 */
@RestController
@RequestMapping("/api/v1/feed")
public class FeedController {

    private final FeedAppService feedAppService;

    public FeedController(FeedAppService feedAppService) {
        this.feedAppService = feedAppService;
    }

    @GetMapping
    public ResponseEntity<Feed> getFeed(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String since) {
        LocalDate queryDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        LocalDateTime sinceTime = since != null ? LocalDateTime.parse(since) : null;
        return ResponseEntity.ok(feedAppService.getFeed(queryDate, sinceTime));
    }
}
