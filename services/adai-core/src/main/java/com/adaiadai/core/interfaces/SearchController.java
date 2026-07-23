package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.search.SearchResult;
import com.adaiadai.core.kernel.search.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SearchController — 全文搜索 API。
 * <p>
 * GET /api/v1/search?q=xxx
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> search(@RequestParam("q") String query) {
        List<SearchResult> results = searchService.search(query);
        return ResponseEntity.ok(Map.of(
                "results", results,
                "total", results.size()
        ));
    }
}
