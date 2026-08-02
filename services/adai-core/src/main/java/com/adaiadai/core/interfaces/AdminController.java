package com.adaiadai.core.interfaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AdminController — adai-admin 系统级浏览端点（data/ 文件树 + os/ 知识资产）。
 * <p>
 * 这些端点读取系统级路径（data/ 全部用户层、os/ 知识库），不走 X-User-Id 用户层，
 * 仅供 adai-admin 管理端使用（v1.0.0）。路径一律 {@code normalize + startsWith} 校验防目录遍历。
 *
 * <pre>
 * GET /api/v1/admin/files?path=           → data/ 目录条目列表
 * GET /api/v1/admin/files/content?path=   → data/ 文件内容
 * GET /api/v1/admin/knowledge?domain=trading-os&path=  → os/{domain}/ 目录条目列表
 * GET /api/v1/admin/knowledge/content?path=            → os/ 文件内容
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /** 文件内容预览上限 512KB，避免一次加载大文件。 */
    private static final long MAX_FILE_SIZE = 512 * 1024;

    private static final Set<String> KNOWN_DOMAINS = Set.of("trading-os", "life-os", "project-os");

    private final Path dataRoot;
    private final Path osRoot;

    public AdminController(@Value("${adai.storage.base-path:../../data}") String dataBasePath,
                           @Value("${adai.os-base-path:../../os}") String osBasePath) {
        this.dataRoot = Paths.get(dataBasePath).toAbsolutePath().normalize();
        this.osRoot = Paths.get(osBasePath).toAbsolutePath().normalize();
    }

    // ── data/ 文件树浏览 ──

    @GetMapping("/files")
    public ResponseEntity<?> listFiles(@RequestParam(defaultValue = "") String path) {
        try {
            Path dir = safeResolve(dataRoot, path);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return ResponseEntity.notFound().build();
            }
            try (Stream<Path> children = Files.list(dir)) {
                List<Map<String, Object>> entries = children
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .map(p -> entry(dataRoot, p))
                        .collect(Collectors.toList());
                return ResponseEntity.ok(entries);
            }
        } catch (Exception e) {
            log.warn("文件树浏览失败 | path={} | {}", path, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/files/content")
    public ResponseEntity<?> getFileContent(@RequestParam String path) {
        return readContent(dataRoot, path);
    }

    // ── os/ 知识资产浏览 ──

    @GetMapping("/knowledge")
    public ResponseEntity<?> listKnowledge(@RequestParam String domain,
                                           @RequestParam(defaultValue = "") String path) {
        if (!KNOWN_DOMAINS.contains(domain)) {
            return ResponseEntity.badRequest().body(Map.of("error", "domain 仅允许 " + KNOWN_DOMAINS));
        }
        // domain 仅做白名单校验；path 决定浏览位置（相对 os/ 根，浏览 os/trading-os/ 传 path=trading-os）
        try {
            Path dir = safeResolve(osRoot, path);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return ResponseEntity.notFound().build();
            }
            try (Stream<Path> children = Files.list(dir)) {
                List<Map<String, Object>> entries = children
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .map(p -> entry(osRoot, p))
                        .collect(Collectors.toList());
                return ResponseEntity.ok(entries);
            }
        } catch (Exception e) {
            log.warn("知识浏览失败 | domain={} | path={} | {}", domain, path, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/knowledge/content")
    public ResponseEntity<?> getKnowledgeContent(@RequestParam String path) {
        return readContent(osRoot, path);
    }

    // ── helpers ──

    /** 解析路径并校验在根内（防目录遍历）。 */
    private Path safeResolve(Path root, String path) {
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法路径: " + path);
        }
        return resolved;
    }

    private String relPath(Path root, Path p) {
        return root.relativize(p).normalize().toString().replace('\\', '/');
    }

    private Map<String, Object> entry(Path root, Path p) {
        boolean isDir = Files.isDirectory(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getFileName().toString());
        m.put("path", relPath(root, p));
        m.put("isDir", isDir);
        if (!isDir) {
            try {
                m.put("size", Files.size(p));
            } catch (IOException e) {
                // ignore
            }
        }
        return m;
    }

    private ResponseEntity<?> readContent(Path root, String path) {
        try {
            Path file = safeResolve(root, path);
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return ResponseEntity.notFound().build();
            }
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件过大（>512KB），不支持预览: " + path));
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of(
                    "path", relPath(root, file),
                    "size", size,
                    "content", content));
        } catch (Exception e) {
            log.warn("读取内容失败 | path={} | {}", path, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
