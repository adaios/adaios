package com.adaiadai.core.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LearnRawAssetTest — 具名原始素材留痕（RFC 20260912 源必留痕铁律，2026-09-12）。
 * <p>
 * 具名留痕是三条能力的共同底座：① 文章失效后仍可复原；② 转写稿命中即**零费用**复用；
 * ③ 幂等（同名覆盖而非堆垃圾文件）。同时必须防路径逃逸（复用 fileStem 同款口径）。
 */
class LearnRawAssetTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final LearnCardFileRepository repository = new LearnCardFileRepository(storage);

    @Test
    void saveRaw_thenReadRaw_roundTrip() {
        repository.saveRaw("adai", "bilibili-BV1xx411c7mD-meta.json", "{\"title\":\"某视频\"}");

        assertEquals("{\"title\":\"某视频\"}",
                repository.readRaw("adai", "bilibili-BV1xx411c7mD-meta.json"));
    }

    @Test
    void saveRaw_landsUnderRawDir_ofOwningUser() {
        repository.saveRaw("adai", "article-abc123-text.txt", "正文");

        assertEquals("正文", storage.read("adai", "learn/_raw/article-abc123-text.txt"));
        assertNull(storage.read("bob", "learn/_raw/article-abc123-text.txt"), "素材按用户隔离");
    }

    @Test
    void readRaw_missing_returnsNull() {
        assertNull(repository.readRaw("adai", "not-there.txt"));
        assertNull(repository.readRaw("adai", null));
        assertNull(repository.readRaw("adai", "  "));
    }

    @Test
    void saveRaw_sameName_overwritesInsteadOfDuplicating() {
        repository.saveRaw("adai", "bilibili-BV1-transcript.txt", "第一版");
        repository.saveRaw("adai", "bilibili-BV1-transcript.txt", "第二版");

        assertEquals("第二版", repository.readRaw("adai", "bilibili-BV1-transcript.txt"));
        List<String> files = storage.listFiles("adai", "learn/_raw");
        assertEquals(1, files.size(), "同名素材是幂等键：重复整理不该堆文件");
    }

    @Test
    void saveRaw_nullContent_storedAsEmpty() {
        repository.saveRaw("adai", "x.txt", null);

        assertEquals("", repository.readRaw("adai", "x.txt"));
    }

    // ── 路径逃逸防护（沿用 fileStem 同款口径）──

    @Test
    void safeRawName_stripsTraversalAndSeparators() {
        String cleaned = LearnCardFileRepository.safeRawName("../../etc/passwd");
        assertFalse(cleaned.contains(".."), "不得保留 ..（防上跳目录）");
        assertFalse(cleaned.contains("/"), "不得保留路径分隔符");
        assertTrue(cleaned.endsWith("etc_passwd"), "可读尾部应保留，便于人工辨认素材");

        assertEquals("a_b.txt", LearnCardFileRepository.safeRawName("a/b.txt"));
        assertFalse(LearnCardFileRepository.safeRawName("..%2F..%2Fx").contains(".."));
    }

    @Test
    void saveRaw_traversalAttempt_staysInsideRawDir() {
        repository.saveRaw("adai", "../../../escape.txt", "危险内容");

        assertTrue(storage.exists("adai", "learn/_raw/" + LearnCardFileRepository.safeRawName("../../../escape.txt")));
        assertNull(storage.read("adai", "escape.txt"), "不得写到 _raw 之外");
        assertNull(storage.read("adai", "learn/escape.txt"));
    }

    @Test
    void safeRawName_blank_throws() {
        assertThrows(com.adaiadai.core.domain.learn.LearnException.class,
                () -> LearnCardFileRepository.safeRawName("  "));
        assertThrows(com.adaiadai.core.domain.learn.LearnException.class,
                () -> LearnCardFileRepository.safeRawName(null));
    }

    // ── 既有非具名留痕仍可用（LLM 失败兜底路径）──

    @Test
    void saveRawSource_stillWorksAlongsideNamedRaw() {
        repository.saveRawSource("adai", "LLM 失败时的素材");

        List<String> files = storage.listFiles("adai", "learn/_raw");
        assertEquals(1, files.size());
        assertTrue(files.get(0).contains("learn_raw_"));
    }

    @Test
    void namedAndFallbackRaw_coexist() {
        repository.saveRaw("adai", "bilibili-BV1-meta.json", "{}");
        repository.saveRawSource("adai", "兜底素材");

        assertEquals(2, storage.listFiles("adai", "learn/_raw").size());
    }
}
