package com.adaiadai.core.kernel.search;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SearchResult — 搜索结果 DTO。
 *
 * @param id         记录 ID
 * @param type       记录类型
 * @param title      标题
 * @param content    内容片段（带高亮标记）
 * @param tags       标签
 * @param dateTime   记录时间
 */
public record SearchResult(
        String id,
        String type,
        String title,
        String content,
        List<String> tags,
        LocalDateTime dateTime
) {}
