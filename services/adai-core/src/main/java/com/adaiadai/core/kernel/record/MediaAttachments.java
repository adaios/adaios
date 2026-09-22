package com.adaiadai.core.kernel.record;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * MediaAttachments — 图文一体的「薄附件」识别（RFC 20260815-media-event-unification）。
 * <p>
 * 一次投递 = **1 条主记录**（带 {@code mediaIds}）+ N 条**薄 image 附件记录**（仅作原图索引：
 * 不做 VLM、不沉淀记忆、{@code summary=图片附件} 哨兵）。
 * <p>
 * 附件只作为主记录的图存在——**任何面向用户或 AI 的记录遍历都不该把它们当独立条目**
 * （否则会冒出 N 条「图片附件」这种系统视角内容，违反第一原则；也会污染喂给阿呆的上下文）。
 * <p>
 * 本类把「谁是被引用的附件」收敛成**单一事实源**：Feed / Timeline / 简报 / 领域活动 /
 * ContextEngine / 搜索都调它，不再各自实现一遍（2026-09-22 图文一体批）。
 */
public final class MediaAttachments {

    private MediaAttachments() {
    }

    /**
     * 收集被主记录 {@code mediaIds} 引用的附件 id。
     * <p>
     * 调用方**算一次、复用**（对每条记录反复调用会退化成 O(n²)）。
     *
     * @param records 一次遍历得到的记录集合（可为 null）
     * @return 附件记录 id 集合（永不为 null）
     */
    public static Set<String> referencedIds(Collection<ContentRecord> records) {
        Set<String> ids = new HashSet<>();
        if (records == null) {
            return ids;
        }
        for (ContentRecord r : records) {
            if (r != null && r.mediaIds() != null && !r.mediaIds().isEmpty()) {
                ids.addAll(r.mediaIds());
            }
        }
        return ids;
    }

    /** 该记录是否是被引用的薄附件（调用方应已持有 {@link #referencedIds} 的结果）。 */
    public static boolean isAttachment(Set<String> referencedIds, ContentRecord record) {
        return record != null && record.id() != null && referencedIds != null
                && referencedIds.contains(record.id());
    }
}
