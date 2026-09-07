package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnTradingCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LearnTradingCandidateFileRepositoryTest — learn → trading 反哺候选 md 存储（RFC 20260829 V2 批 3）。
 * <p>
 * 验证：save/find/list round-trip（frontmatter + 建议段）、候选落 trading/candidates/ 目录、
 * 同日同名防覆盖、created 倒序、多用户隔离、损坏文件跳过、delete 幂等。
 */
class LearnTradingCandidateFileRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private LearnTradingCandidateFileRepository repository;

    @BeforeEach
    void setUp() {
        repository = new LearnTradingCandidateFileRepository(storage);
    }

    private LearnTradingCandidate sample(String title, LocalDate created) {
        return new LearnTradingCandidate(title,
                "learn/trading/2026-09-06_回调一半的判定",
                "trading", created,
                "回调到一半才是买点，几何口径 (high+low)/2",
                List.of("02:31 回调一半=(high+low)/2", "与 R66 止损互补"),
                "与 R66 止损互补", List.of("止损", "回调"));
    }

    @Test
    void saveAndFind_roundTrip_preservesAllFields() {
        LearnTradingCandidate c = sample("回调一半的判定", LocalDate.of(2026, 9, 7));
        repository.save("adai", c);

        Optional<LearnTradingCandidate> loaded = repository.find("adai", c.title());
        assertTrue(loaded.isPresent());
        LearnTradingCandidate got = loaded.get();
        assertEquals("回调一半的判定", got.title());
        assertEquals("learn/trading/2026-09-06_回调一半的判定", got.learnCardId());
        assertEquals("trading", got.sourceType());
        assertEquals(LocalDate.of(2026, 9, 7), got.created());
        assertEquals("回调到一半才是买点，几何口径 (high+low)/2", got.coreView());
        assertEquals(2, got.keyPoints().size());
        assertEquals("与 R66 止损互补", got.tradeNote());
        assertEquals(List.of("止损", "回调"), got.tags());
    }

    @Test
    void fileLandsUnderTradingCandidatesDir() {
        LearnTradingCandidate c = sample("回调一半的判定", LocalDate.of(2026, 9, 7));
        repository.save("adai", c);
        List<String> files = storage.listFiles("adai", "trading/candidates");
        assertEquals(1, files.size());
        assertTrue(files.get(0).startsWith("trading/candidates/2026-09-07_回调一半的判定.md"),
                "候选应落 trading/candidates/ 目录，实际: " + files.get(0));
        // learn 卡片区不受影响（候选不复制进 learn/）
        assertTrue(storage.listFiles("adai", "learn").isEmpty());
    }

    @Test
    void save_sameTitleSameDate_throwsNoOverwrite() {
        repository.save("adai", sample("回调一半的判定", LocalDate.of(2026, 9, 7)));
        LearnException ex = assertThrows(LearnException.class,
                () -> repository.save("adai", sample("回调一半的判定", LocalDate.of(2026, 9, 7))));
        assertTrue(ex.getMessage().contains("同名"), "人话提示已有候选: " + ex.getMessage());
    }

    @Test
    void list_sortedByCreatedDesc() {
        repository.save("adai", sample("第一篇", LocalDate.of(2026, 9, 5)));
        repository.save("adai", sample("第三篇", LocalDate.of(2026, 9, 7)));
        repository.save("adai", sample("第二篇", LocalDate.of(2026, 9, 6)));
        List<LearnTradingCandidate> list = repository.list("adai");
        assertEquals(3, list.size());
        assertEquals("第三篇", list.get(0).title(), "created 倒序");
        assertEquals("第一篇", list.get(2).title());
    }

    @Test
    void multiUser_isolated() {
        repository.save("adai", sample("回调一半的判定", LocalDate.of(2026, 9, 7)));
        assertTrue(repository.find("adai", "回调一半的判定").isPresent());
        assertFalse(repository.find("bob", "回调一半的判定").isPresent());
        assertTrue(repository.list("bob").isEmpty());
    }

    @Test
    void corruptedFile_skippedInList() {
        repository.save("adai", sample("好卡", LocalDate.of(2026, 9, 7)));
        storage.write("adai", "trading/candidates/2026-09-06_损坏.md", "这不是 frontmatter 格式");
        List<LearnTradingCandidate> list = repository.list("adai");
        assertEquals(1, list.size());
        assertEquals("好卡", list.get(0).title());
    }

    @Test
    void delete_removesCandidate_andIsIdempotent() {
        repository.save("adai", sample("回调一半的判定", LocalDate.of(2026, 9, 7)));
        repository.delete("adai", "回调一半的判定");
        assertTrue(repository.list("adai").isEmpty());
        repository.delete("adai", "回调一半的判定");  // 幂等：不存在不抛
        assertTrue(repository.list("adai").isEmpty());
    }

    @Test
    void fileStemSanitized_pathEscapeBlocked() {
        LearnTradingCandidate evil = sample("../逃逸", LocalDate.of(2026, 9, 7));
        repository.save("adai", evil);
        List<String> files = storage.listFiles("adai", "trading/candidates");
        assertEquals(1, files.size());
        assertFalse(files.get(0).contains(".."), "路径逃逸应被清洗: " + files.get(0));
    }
}
