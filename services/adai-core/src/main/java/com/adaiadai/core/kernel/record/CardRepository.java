package com.adaiadai.core.kernel.record;

import java.util.Optional;

/**
 * CardRepository — 卡片对话存储端口（kernel 定义，infrastructure 实现）。
 * <p>
 * REVIEW #22 依赖倒置：ContextEngine 等 kernel 组件只依赖本接口，
 * 具体文件实现（CardFileRepository）归 infrastructure/storage。
 */
public interface CardRepository {

    /**
     * 按 ID 查找卡片。
     *
     * @param userId 用户 ID
     * @param id     卡片 ID（card_ 前缀）
     * @return 卡片，不存在返回 empty
     */
    Optional<CardRecord> findById(String userId, String id);
}
