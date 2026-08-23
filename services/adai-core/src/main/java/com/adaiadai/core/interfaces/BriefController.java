package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.BriefAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * BriefController — AI 简报 API。
 * <p>
 * 每天第一次打开 App 时调用，返回 AI 生成的自然语言今日简报。
 */
@RestController
@RequestMapping("/api/v1/brief")
public class BriefController {

    private final BriefAppService briefAppService;

    public BriefController(BriefAppService briefAppService) {
        this.briefAppService = briefAppService;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> getBrief(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        String brief = briefAppService.generateBrief(userId);
        return ResponseEntity.ok(Map.of("content", brief));
    }

    /**
     * 只返回 5 分钟内的缓存 Brief，不触发 AI 生成（可能为空串）。
     * 前端首屏用它避免主页加载被 AI 生成阻塞；空串时再异步调 {@link #getBrief} 补全。
     */
    @GetMapping("/cached")
    public ResponseEntity<Map<String, String>> getCachedBrief(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        String brief = briefAppService.getCachedBrief(userId);
        return ResponseEntity.ok(Map.of("content", brief));
    }
}
