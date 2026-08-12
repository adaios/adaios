package com.adaiadai.core.kernel.context.engine;

import java.util.List;

/**
 * TagIndexReader — 标签索引读取端口（kernel 定义，infrastructure 实现）。
 * <p>
 * REVIEW #22 依赖倒置：ContextEngine 等 kernel 组件只依赖本接口的只读能力，
 * 标签索引的写入/重建（onRecordSaved/rebuild 等）仍归 infrastructure/storage.TagIndexService。
 */
public interface TagIndexReader {

    /**
     * 根据标签集合找到关联记录 ID（按相关度/时间排序，最多 maxResults 条）。
     *
     * @param userId      用户 ID
     * @param tags        标签集合
     * @param maxResults  结果上限
     * @return 关联记录 ID 列表
     */
    List<String> findRelatedIds(String userId, List<String> tags, int maxResults);
}
