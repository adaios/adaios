package com.adaiadai.core.kernel.search;

import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SearchService — 全文搜索服务。
 * <p>
 * 遍历所有 records 文件，对标题和正文进行模糊匹配。
 * MVP 阶段不做索引，直接线性扫描。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final RecordRepository recordRepository;

    public SearchService(RecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    /**
     * 全文搜索。
     *
     * @param query 搜索关键词
     * @return 匹配的记录列表
     */
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String q = query.trim().toLowerCase();
        List<ContentRecord> all = recordRepository.findAll();

        return all.stream()
                .filter(r -> matches(r, q))
                .map(r -> new SearchResult(
                        r.id(),
                        r.type(),
                        r.title() != null ? r.title() : "",
                        highlight(r.content(), q),
                        r.tags(),
                        r.createdAt()
                ))
                .toList();
    }

    private boolean matches(ContentRecord record, String query) {
        if (record.title() != null && record.title().toLowerCase().contains(query)) {
            return true;
        }
        if (record.content() != null && record.content().toLowerCase().contains(query)) {
            return true;
        }
        if (record.tags() != null) {
            for (String tag : record.tags()) {
                if (tag.toLowerCase().contains(query)) {
                    return true;
                }
            }
        }
        return record.summary() != null && record.summary().toLowerCase().contains(query);
    }

    /**
     * 在内容中截取匹配片段（前后各 30 字）。
     */
    private String highlight(String content, String query) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int idx = content.toLowerCase().indexOf(query);
        if (idx < 0) {
            return content.substring(0, Math.min(content.length(), 100));
        }
        int start = Math.max(0, idx - 30);
        int end = Math.min(content.length(), idx + query.length() + 30);
        String snippet = content.substring(start, end);
        if (start > 0) snippet = "..." + snippet;
        if (end < content.length()) snippet = snippet + "...";
        return snippet;
    }
}
