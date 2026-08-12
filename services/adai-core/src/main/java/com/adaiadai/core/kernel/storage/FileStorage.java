package com.adaiadai.core.kernel.storage;

import java.util.List;

/**
 * FileStorage — 文件存储抽象。
 * <p>
 * 支撑 File First 原则的核心接口。所有个人资产通过此接口读写文件系统。
 * <p>
 * 多用户架构预留（2026-08-02）：所有方法带 {@code userId}，实际存储路径按用户分层
 * {@code data/{userId}/...}。单用户时传 {@code "default"}。
 */
public interface FileStorage {

    /**
     * 将内容写入文件（如果父目录不存在则自动创建）。
     *
     * @param userId  用户 ID（单用户传 "default"），路径将落在 {@code data/{userId}/} 下
     * @param path    相对用户层的路径（如 {@code records/2026/07/rec_20260712_143000.md}）
     * @param content 文件内容
     */
    void write(String userId, String path, String content);

    /**
     * 读取文件内容。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param path   相对用户层的路径
     * @return 文件内容，文件不存在则返回 null
     */
    String read(String userId, String path);

    /**
     * 列出指定目录下的所有文件路径（递归，限定在该用户层内）。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param dir    相对用户层的目录路径
     * @return 文件相对路径列表
     */
    List<String> listFiles(String userId, String dir);

    /**
     * 判断文件或目录是否存在。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param path   相对用户层的路径
     * @return 是否存在
     */
    boolean exists(String userId, String path);

    /**
     * 将二进制内容写入文件（如图片/音频），父目录不存在则自动创建。
     * 多模态记录（L4）的原始资产通过此接口落盘。
     *
     * @param userId  用户 ID（单用户传 "default"）
     * @param path    相对用户层的路径（如 {@code records/2026/08/media/rec_x.png}）
     * @param content 文件字节内容
     */
    void writeBytes(String userId, String path, byte[] content);

    /**
     * 读取二进制文件内容；文件不存在返回 null。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param path   相对用户层的路径
     * @return 文件字节内容，文件不存在返回 null
     */
    byte[] readBytes(String userId, String path);

    /**
     * 删除文件。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param path   相对用户层的路径
     */
    void delete(String userId, String path);

    /**
     * 追加内容到文件末尾（文件不存在则创建）。
     * <p>
     * 用于追加型日志（如 {@code ai-logs/**} JSONL）：与 {@link #write} 的覆盖语义不同，
     * 追加不读取-拼接整文件，避免日志文件随条目增长出现 O(N²) 读写。
     *
     * @param userId  用户 ID（单用户传 "default"），路径将落在 {@code data/{userId}/} 下
     * @param path    相对用户层的路径
     * @param content 追加的内容（调用方保证以换行结尾）
     */
    void append(String userId, String path, String content);
}
