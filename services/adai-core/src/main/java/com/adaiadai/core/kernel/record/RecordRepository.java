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
     * @param userId 用户 ID（单用户传 "default"）
     * @param record 要保存的记录
     */
    void save(String userId, ContentRecord record);

    /**
     * 根据 ID 查找一条 Record。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param id     记录唯一标识
     * @return 匹配的记录
     */
    Optional<ContentRecord> findById(String userId, String id);

    /**
     * 按时间倒序返回该用户的所有记录。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @return 所有记录列表（最近的在最前）
     */
    List<ContentRecord> findAll(String userId);

    /**
     * 根据 ID 删除一条记录。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param id     记录唯一标识
     */
    void deleteById(String userId, String id);

    /**
     * 更新记录的 domain 字段。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param id     记录 ID
     * @param domain 新域名值（life / trading / project）
     */
    void updateDomain(String userId, String id, String domain);
}
