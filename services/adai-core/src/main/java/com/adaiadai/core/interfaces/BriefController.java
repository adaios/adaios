package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.BriefAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    public ResponseEntity<Map<String, String>> getBrief() {
        String brief = briefAppService.generateBrief();
        return ResponseEntity.ok(Map.of("content", brief));
    }
}
