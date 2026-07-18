package com.adaiadai.core.infrastructure.storage;

import java.util.List;

/**
 * FileStorage — 文件存储抽象。
 * <p>
 * 支撑 File First 原则的核心接口。所有个人资产通过此接口读写文件系统。
 */
public interface FileStorage {

    /**
     * 将内容写入文件（如果父目录不存在则自动创建）。
     *
     * @param path    相对路径（如 {@code records/2026/07/rec_20260712_143000.md}）
     * @param content 文件内容
     */
    void write(String path, String content);

    /**
     * 读取文件内容。
     *
     * @param path 相对路径
     * @return 文件内容，文件不存在则返回空
     */
    String read(String path);

    /**
     * 列出指定目录下的所有文件路径（递归）。
     *
     * @param dir 相对目录路径
     * @return 文件相对路径列表
     */
    List<String> listFiles(String dir);

    /**
     * 判断文件或目录是否存在。
     *
     * @param path 相对路径
     * @return 是否存在
     */
    boolean exists(String path);
}
