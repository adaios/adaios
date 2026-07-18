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
    public void write(String path, String content) {
        try {
            Path target = resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            log.debug("文件写入成功: {}", target);
        } catch (IOException e) {
            throw new StorageException("写入文件失败: " + path, e);
        }
    }

    @Override
    public String read(String path) {
        try {
            Path target = resolve(path);
            if (!Files.exists(target)) {
                return null;
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("读取文件失败: " + path, e);
        }
    }

    @Override
    public List<String> listFiles(String dir) {
        try {
            Path target = basePath.resolve(dir);
            if (!Files.exists(target) || !Files.isDirectory(target)) {
                return Collections.emptyList();
            }
            try (Stream<Path> walk = Files.walk(target)) {
                return walk
                        .filter(Files::isRegularFile)
                        .map(p -> basePath.relativize(p).normalize().toString().replace('\\', '/'))
                        .sorted()
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new StorageException("列出目录文件失败: " + dir, e);
        }
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(resolve(path));
    }

    private Path resolve(String path) {
        // 防止路径遍历攻击
        Path resolved = basePath.resolve(path).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new StorageException("非法路径访问: " + path);
        }
        return resolved;
    }
}
