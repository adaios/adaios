package com.adaiadai.core.kernel.record;

import java.util.List;
import java.util.Optional;

/**
 * RecordRepository — Record 的存储接口（端口定义，采用依赖倒置原则）。
 * <p>
 * 定义 Record 的存取契约，实现由 {@code infrastructure.storage} 提供。
 * 遵循 File First：所有 Record 以文件形式存取，此接口抽象了文件读写细节。
 */
public interface RecordRepository {

    /**
     * 保存一条 Record（写入文件系统）。
     *
     * @param record 要保存的记录
     */
    void save(ContentRecord record);

    /**
     * 根据 ID 查找一条 Record。
     *
     * @param id 记录唯一标识
     * @return 匹配的记录
     */
    Optional<ContentRecord> findById(String id);

    /**
     * 按时间倒序返回所有记录。
     *
     * @return 所有记录列表（最近的在最前）
     */
    List<ContentRecord> findAll();
}
