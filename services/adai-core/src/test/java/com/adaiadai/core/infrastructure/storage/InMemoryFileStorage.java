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

    private static final java.util.Base64.Encoder B64 = java.util.Base64.getEncoder();
    private static final java.util.Base64.Decoder B64D = java.util.Base64.getDecoder();
    private static final String BYTES_PREFIX = "b64:";

    @Override
    public void write(String userId, String path, String content) {
        store.put(key(userId, path), content);
    }

    @Override
    public void writeBytes(String userId, String path, byte[] content) {
        store.put(key(userId, path), BYTES_PREFIX + B64.encodeToString(content));
    }

    @Override
    public byte[] readBytes(String userId, String path) {
        String v = store.get(key(userId, path));
        if (v == null) return null;
        if (v.startsWith(BYTES_PREFIX)) return B64D.decode(v.substring(BYTES_PREFIX.length()));
        return null;
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

    @Override
    public void append(String userId, String path, String content) {
        String key = key(userId, path);
        store.merge(key, content, (a, b) -> a + b);
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
