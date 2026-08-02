package com.adaiadai.core.infrastructure.storage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 内存文件存储 — 测试用，不写磁盘。
 * 替代 LocalFileStorage 用于单元测试。
 * 多用户预留：内部 key 按 {@code userId/path} 存储，listFiles 返回相对用户层路径（与 LocalFileStorage 一致）。
 */
public class InMemoryFileStorage implements FileStorage {

    private final Map<String, String> store = new LinkedHashMap<>();

    @Override
    public void write(String userId, String path, String content) {
        store.put(key(userId, path), content);
    }

    @Override
    public String read(String userId, String path) {
        return store.get(key(userId, path));
    }

    @Override
    public List<String> listFiles(String userId, String dir) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        String raw = normalize(dir);
        String prefix = uid + "/" + (raw.isEmpty() || raw.endsWith("/") ? raw : raw + "/");
        return store.keySet().stream()
                .filter(k -> k.startsWith(prefix) && !k.equals(prefix))
                .map(k -> k.substring(uid.length() + 1))
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public boolean exists(String userId, String path) {
        return store.containsKey(key(userId, path));
    }

    @Override
    public void delete(String userId, String path) {
        store.remove(key(userId, path));
    }

    public void clear() {
        store.clear();
    }

    private String key(String userId, String path) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        return uid + "/" + normalize(path);
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }
}
