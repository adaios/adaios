package com.adaiadai.core.kernel.rhythm;

import java.util.List;
import java.util.Optional;

/**
 * RhythmRepository — 节律存储接口（端口定义，RFC 20260923 B 批）。
 * <p>
 * 定义在 kernel/rhythm（与 todo/memory 同级的基础能力），实现由 infrastructure/storage 提供。
 * File First：节律以 {@code data/{userId}/rhythm/YYYY/MM.md} 文件存储。
 */
public interface RhythmRepository {

    /** 查找该用户节律（可按状态筛选，status 为 null 表示全部）。 */
    List<Rhythm> findAll(RhythmStatus status, String userId);

    /** 查找该用户全部节律。 */
    List<Rhythm> findAll(String userId);

    /** 按 ID 查找节律。 */
    Optional<Rhythm> findById(String userId, String id);

    /** 保存节律（新增或更新）。 */
    void save(String userId, Rhythm rhythm);

    /** 删除节律。 */
    void delete(String userId, String id);
}
