package com.adaiadai.core.infrastructure.storage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 内存文件存储 — 测试用，不写磁盘。
 * 替代 LocalFileStorage 用于单元测试。
 */
public class InMemoryFileStorage implements FileStorage {

    private final Map<String, String> store = new LinkedHashMap<>();

    @Override
    public void write(String path, String content) {
        store.put(normalize(path), content);
    }

    @Override
    public String read(String path) {
        return store.get(normalize(path));
    }

    @Override
    public List<String> listFiles(String dir) {
        String raw = normalize(dir);
        String prefix = raw.isEmpty() || raw.endsWith("/") ? raw : raw + "/";
        return store.keySet().stream()
                .filter(k -> k.startsWith(prefix) && !k.equals(prefix))
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public boolean exists(String path) {
        return store.containsKey(normalize(path));
    }

    public void clear() {
        store.clear();
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }
}
