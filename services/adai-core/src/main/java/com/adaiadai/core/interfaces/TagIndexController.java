package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.storage.TagIndexService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * TagIndexController — 标签统计查询 API。
 * <p>
 * Launcher 页调用此接口获取所有标签及其统计信息。
 */
@RestController
@RequestMapping("/api/v1/tags")
public class TagIndexController {

    private final TagIndexService tagIndexService;

    public TagIndexController(TagIndexService tagIndexService) {
        this.tagIndexService = tagIndexService;
    }

    /**
     * 获取所有标签统计。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getTags() {
        List<TagIndexService.TagSummary> tags = tagIndexService.getAllTags();
        return ResponseEntity.ok(Map.of(
                "tags", tags,
                "total", tags.size(),
                "updatedAt", java.time.LocalDateTime.now()
        ));
    }
}
