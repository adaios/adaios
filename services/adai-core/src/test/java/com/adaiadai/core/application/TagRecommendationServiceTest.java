package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TagRecommendationService 单元测试。
 */
class TagRecommendationServiceTest {

    private InMemoryFileStorage fileStorage;
    private TagIndexService tagIndexService;
    private TagRecommendationService tagRecommendationService;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        tagIndexService = new TagIndexService(fileStorage);
        tagRecommendationService = new TagRecommendationService(tagIndexService);
    }

    @Test
    void getRecommendations_emptyIndex_returnsEmpty() {
        TagRecommendationService.TagRecommendations recs = tagRecommendationService.getRecommendations();

        assertTrue(recs.hot().isEmpty());
        assertTrue(recs.cold().isEmpty());
    }

    @Test
    void getRecommendations_oldTag_becomesCold() {
        // 直接写 tags.json 模拟一个超过 14 天没出现的标签
        String oldJson = """
                {
                  "tags": {
                    "读书": {
                      "count": 5,
                      "recordIds": ["rec_20250701_100000", "rec_20250601_100000"],
                      "firstAt": "2025-06-01T10:00:00",
                      "lastAt": "2025-07-01T10:00:00"
                    }
                  },
                  "updatedAt": "2025-07-01T10:00:00"
                }
                """;
        fileStorage.write("index/tags.json", oldJson);

        TagRecommendationService.TagRecommendations recs = tagRecommendationService.getRecommendations();

        assertTrue(recs.cold().contains("读书"));
    }

    @Test
    void getRecommendations_recentTag_isHot() {
        String now = LocalDateTime.now().toString();
        String json = String.format("""
                {
                  "tags": {
                    "健身": {
                      "count": 3,
                      "recordIds": ["rec_20260726_100000"],
                      "firstAt": "%s",
                      "lastAt": "%s"
                    }
                  },
                  "updatedAt": "%s"
                }
                """, now, now, now);
        fileStorage.write("index/tags.json", json);

        TagRecommendationService.TagRecommendations recs = tagRecommendationService.getRecommendations();

        assertTrue(recs.hot().contains("健身"));
    }

    @Test
    void getRecommendations_lowCountTag_notCold() {
        // 只记过 1 次的标签不应该被标记为 cold
        String oldJson = """
                {
                  "tags": {
                    "偶尔话题": {
                      "count": 1,
                      "recordIds": ["rec_20250601_100000"],
                      "firstAt": "2025-06-01T10:00:00",
                      "lastAt": "2025-06-01T10:00:00"
                    }
                  },
                  "updatedAt": "2025-06-01T10:00:00"
                }
                """;
        fileStorage.write("index/tags.json", oldJson);

        TagRecommendationService.TagRecommendations recs = tagRecommendationService.getRecommendations();

        assertFalse(recs.cold().contains("偶尔话题"),
                "只记过 1 次的标签不应该被标记为 cold");
    }
}
