package com.adaiadai.core.infrastructure.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * LocalFileStorage — 基于本地文件系统的存储实现。
 * <p>
 * 基础路径由配置项 {@code adai.storage.base-path} 指定，默认为项目根目录下的 {@code data/}。
 */
@Component
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path basePath;

    public LocalFileStorage(@Value("${adai.storage.base-path:data}") String basePath) {
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        log.info("FileStorage 基础路径: {}", basePath);
    }

    @Override
    public void write(String userId, String path, String content) {
        try {
            Path target = resolve(userId, path);
            atomicWrite(target, content.getBytes(StandardCharsets.UTF_8));
            log.debug("文件写入成功: {}", target);
        } catch (IOException e) {
            throw new StorageException("写入文件失败: " + path, e);
        }
    }

    @Override
    public String read(String userId, String path) {
        try {
            Path target = resolve(userId, path);
            if (!Files.exists(target)) {
                return null;
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("读取文件失败: " + path, e);
        }
    }

    @Override
    public List<String> listFiles(String userId, String dir) {
        try {
            Path target = resolve(userId, dir);
            if (!Files.exists(target) || !Files.isDirectory(target)) {
                return Collections.emptyList();
            }
            try (Stream<Path> walk = Files.walk(target)) {
                return walk
                        .filter(Files::isRegularFile)
                        .map(p -> basePath.relativize(p).normalize().toString().replace('\\', '/'))
                        .map(p -> stripUserPrefix(p, userId))
                        .sorted()
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new StorageException("列出目录文件失败: " + dir, e);
        }
    }

    @Override
    public boolean exists(String userId, String path) {
        return Files.exists(resolve(userId, path));
    }

    @Override
    public void writeBytes(String userId, String path, byte[] content) {
        try {
            Path target = resolve(userId, path);
            atomicWrite(target, content);
            log.debug("二进制文件写入成功: {}", target);
        } catch (IOException e) {
            throw new StorageException("写入文件失败: " + path, e);
        }
    }

    /**
     * 原子写入：临时文件 + ATOMIC_MOVE，避免崩溃/断电中途写坏单文件存储（P0 #126）。
     * 同目录 rename 在 POSIX 下原子，替换前旧内容始终完整可读；失败抛异常保留原文件。
     */
    private void atomicWrite(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // 文件系统不支持原子 rename 时降级普通替换（极端文件系统，保留原有行为）
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public byte[] readBytes(String userId, String path) {
        try {
            Path target = resolve(userId, path);
            if (!Files.exists(target)) {
                return null;
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new StorageException("读取文件失败: " + path, e);
        }
    }

    @Override
    public void delete(String userId, String path) {
        try {
            Files.deleteIfExists(resolve(userId, path));
            log.debug("文件删除成功: {}", resolve(userId, path));
        } catch (IOException e) {
            throw new StorageException("删除文件失败: " + path, e);
        }
    }

    @Override
    public void append(String userId, String path, String content) {
        try {
            Path target = resolve(userId, path);
            Files.createDirectories(target.getParent());
            // O_APPEND：单次 write 追加，并发交错由调用方同步（AiInteractionLogger 内部已加锁）
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("文件追加成功: {}", target);
        } catch (IOException e) {
            throw new StorageException("追加写入文件失败: " + path, e);
        }
    }

    /**
     * 解析实际路径：{@code basePath/{userId}/{path}}，统一加用户层（多用户架构预留）。
     * userId 归一化校验（仅 {@code [a-zA-Z0-9_-]}），防止路径注入。
     */
    private Path resolve(String userId, String path) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        if (!uid.matches("[a-zA-Z0-9_-]+")) {
            throw new StorageException("非法用户 ID: " + userId);
        }
        // 防止路径遍历攻击
        Path resolved = basePath.resolve(uid).resolve(path).normalize();
        Path userRoot = basePath.resolve(uid).normalize();
        if (!resolved.startsWith(userRoot)) {
            throw new StorageException("非法路径访问: " + path);
        }
        return resolved;
    }

    /**
     * 去掉返回路径中的用户层前缀，保持与传入时一致（相对用户层）。
     * 如 {@code default/records/2026/07/a.md} → {@code records/2026/07/a.md}。
     */
    private String stripUserPrefix(String fullPath, String userId) {
        String prefix = userId + "/";
        return fullPath.startsWith(prefix) ? fullPath.substring(prefix.length()) : fullPath;
    }
}
