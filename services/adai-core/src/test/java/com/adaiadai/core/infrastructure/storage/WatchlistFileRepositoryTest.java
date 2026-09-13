package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * WatchlistFileRepository — 自选股持久化（2026-09-13 P2-交易41 同型风险封堵批）。
 *
 * <p>本文件只守一件事：<b>写盘失败必须抛错，不得被 log.warn 吞掉</b>。
 * 原实现 catch 后只 warn，导入端点照旧返回「成功 N 只」而文件根本没写——
 * 用户以为自选已被新文件覆盖，实际还是旧列表（信任炸弹）；
 * 对齐 {@code SoldTradeFileRepository.writeAll} 的 P1-3 口径（P0-1 fail-visible）。
 */
class WatchlistFileRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final WatchlistFileRepository repo = new WatchlistFileRepository(storage);

    private WatchlistItem item(String symbol, String name) {
        return new WatchlistItem(symbol, name, "元器件", "信息产业-元器件", 6, 8, 1, "KDJ死叉",
                LocalDate.of(2026, 8, 19));
    }

    @Test
    void save_find_roundtrip_andOverwrite() {
        repo.saveAll("adai", List.of(item("000725", "京东方Ａ"), item("601066", "中信建投")));

        List<WatchlistItem> loaded = repo.findAll("adai");
        assertEquals(2, loaded.size());
        assertEquals("000725", loaded.get(0).symbol());
        assertEquals("KDJ死叉", loaded.get(0).signal());
        assertEquals(LocalDate.of(2026, 8, 19), loaded.get(0).addedAt());

        // 覆盖语义（自选 = 最后一次导入的镜像）：文件外条目消失
        repo.saveAll("adai", List.of(item("600487", "亨通光电")));
        assertEquals(1, repo.findAll("adai").size());
        assertEquals("600487", repo.findAll("adai").get(0).symbol());
    }

    @Test
    void saveAll_writeFailure_throwsStorageException_notSwallowed() {
        FileStorage failing = mock(FileStorage.class);
        doThrow(new RuntimeException("磁盘满了")).when(failing).write(anyString(), anyString(), anyString());
        WatchlistFileRepository failingRepo = new WatchlistFileRepository(failing);

        StorageException ex = assertThrows(StorageException.class,
                () -> failingRepo.saveAll("adai", List.of(item("000725", "京东方Ａ"))),
                "写盘失败必须抛错——否则导入端点会回「成功 N 只」而文件没写（信任炸弹）");
        assertTrue(ex.getMessage().contains("保存自选股失败"), "错误信息要能定位： " + ex.getMessage());
    }

    @Test
    void archive_beforeReplace_keepsPreviousContent() {
        repo.saveAll("adai", List.of(item("000725", "京东方Ａ")));
        repo.archive("adai", "20260913-120000");

        // 归档后覆盖写入：当前列表换新，归档文件仍保留旧列表（覆盖策略的撤销保险）
        repo.saveAll("adai", List.of(item("600487", "亨通光电")));
        assertEquals("600487", repo.findAll("adai").get(0).symbol());

        String archived = storage.read("adai", "trading/watchlist.json.bak-20260913-120000");
        assertTrue(archived != null && archived.contains("000725"),
                "归档文件应保留被覆盖前的旧列表，实际: " + archived);
    }
}
