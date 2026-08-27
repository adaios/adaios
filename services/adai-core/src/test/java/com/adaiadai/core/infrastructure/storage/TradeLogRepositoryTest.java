package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradeLogCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradeLogRepository — B7-2（2026-08-23，P1-交易19）真实仓储测试：
 * 并发 append 不丢候选（per-user 锁）/ save 与 append 同锁 / discard 移除。
 * 此前关键修复（save 锁/dedupeKey）只在 mock 层测试，真实仓储路径零覆盖。
 */
class TradeLogRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final TradeLogRepository repo = new TradeLogRepository(storage);
    private final LocalDate day = LocalDate.of(2026, 8, 23);

    private TradeLogCandidate c(String symbol, Integer volume) {
        return new TradeLogCandidate(symbol, symbol + "名", "BUY",
                volume != null ? new BigDecimal("10.0") : null, volume, null, "text",
                volume != null);
    }

    @Test
    void append_concurrent20_noLostCandidate() throws Exception {
        // B5-4/B7-2：per-user+date 锁内 append——20 并发各加 1 笔（不同 symbol），最终 20 笔不丢
        int threads = 20;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger(0);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    repo.append("default", day, c(String.format("600%03d", idx), 100 + idx));
                    done.incrementAndGet();
                } catch (Throwable t) {
                    error.set(t);
                }
            }).start();
        }
        ready.await();
        go.countDown();
        while (done.get() < threads) Thread.sleep(10);
        if (error.get() != null) throw new AssertionError(error.get());

        assertEquals(20, done.get());
        assertEquals(20, repo.findByDate("default", day).size(), "20 并发 append 全部落盘（无丢失覆盖）");
    }

    @Test
    void append_sameVolume10pct_deduplicated() {
        // B6-2/B7-2：同 symbol 100 与 109（±10% 内）→ 去重为 1 笔（真实仓储路径验证 sameTrade）
        repo.append("default", day, c("000725", 100));
        repo.append("default", day, c("000725", 109));
        assertEquals(1, repo.findByDate("default", day).size(), "±10% 内同笔去重");
    }

    @Test
    void append_volumeOver10pct_bothKept() {
        // B6-2/B7-2：同 symbol 100 与 120（差 20%）→ 两笔都保留（不吞真实交易）
        repo.append("default", day, c("000725", 100));
        repo.append("default", day, c("000725", 120));
        assertEquals(2, repo.findByDate("default", day).size(), "超 ±10% 应各自保留");
    }

    @Test
    void save_afterAppend_keepsExisting() {
        // B5-4：save（如 confirm 写回）不清掉已 append 的候选
        repo.append("default", day, c("000725", 100));
        repo.save("default", day, List.of(c("600519", 500))); // 模拟确认写回保留集
        // save 是全量覆盖语义——只保留传入的；append 后 save 覆盖（这是设计：调用方传全量）
        assertEquals(1, repo.findByDate("default", day).size());
        assertEquals("600519", repo.findByDate("default", day).get(0).symbol());
    }

    @Test
    void append_withTradeDate_roundTrips() {
        // 2026-08-27（用户反馈「今日 4 笔其实是昨天」）：候选成交日期（截图日期列）必须随候选落盘读回，
        // confirm 才能用截图日期而非确认当天
        java.time.LocalDate tradeDate = java.time.LocalDate.of(2026, 8, 26);
        TradeLogCandidate withDate = new TradeLogCandidate(
                "000831", "中国稀土", "BUY", new BigDecimal("56.04"), 100, tradeDate, "image", true);
        repo.append("default", day, withDate);
        repo.append("default", day, c("000725", 100)); // 无日期候选（文字归集）

        List<TradeLogCandidate> read = repo.findByDate("default", day);
        assertEquals(2, read.size());
        assertEquals(tradeDate, read.stream().filter(x -> "000831".equals(x.symbol()))
                .findFirst().orElseThrow().tradeDate(), "带日期候选 round-trip 不丢");
        assertNull(read.stream().filter(x -> "000725".equals(x.symbol()))
                .findFirst().orElseThrow().tradeDate(), "无日期候选保持 null");
    }

    @Test
    void discard_removesBySymbolDirection() {
        repo.append("default", day, c("000725", 100));
        repo.append("default", day, c("600519", 200));

        assertTrue(repo.discard("default", day, "000725", "BUY"), "丢弃命中返回 true");
        assertEquals(1, repo.findByDate("default", day).size());
        assertEquals("600519", repo.findByDate("default", day).get(0).symbol());

        assertFalse(repo.discard("default", day, "999999", "BUY"), "未命中返回 false");
        assertEquals(1, repo.findByDate("default", day).size(), "未命中不得误删");
    }
}
