package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnDigestTask;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LearnDigestTaskFileRepositoryTest — 「整理任务」追踪账落盘（2026-09-23 分享追踪批）。
 *
 * <p>覆盖：往返读写 / 同 id 原地覆盖（状态推进不产生重复行）/ 新→旧顺序 / 滚动窗口截断 /
 * **两条 fail-open 兜底**（文件损坏、写盘失败都不得把已经成功的整理拖成失败）。
 */
class LearnDigestTaskFileRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final LearnDigestTaskFileRepository repository = new LearnDigestTaskFileRepository(storage);

    private static LearnDigestTask task(String id, String status, String title) {
        return new LearnDigestTask(id, "https://mp.weixin.qq.com/s/jsOBc6WCH", "央行报告", "mp.weixin.qq.com",
                status, null, null, "other", title, "中国货币政策",
                "2026-09-23T23:06:05", "done".equals(status) ? "2026-09-23T23:06:24" : null);
    }

    @Test
    void save_thenFindRecent_roundTrip() {
        repository.save("adai", task("dtask_1", "done", "央行2025Q4货币政策报告要点"));

        List<LearnDigestTask> tasks = repository.findRecent("adai", 10);

        assertEquals(1, tasks.size());
        LearnDigestTask t = tasks.get(0);
        assertEquals("dtask_1", t.id());
        assertEquals("done", t.status());
        assertEquals("央行2025Q4货币政策报告要点", t.title());
        assertEquals("2026-09-23T23:06:05", t.submittedAt());
        assertEquals("2026-09-23T23:06:24", t.settledAt());
        assertTrue(t.settled());
    }

    /** 状态推进（running → done）是同一把钥匙反复写：**必须原地覆盖，不能留两行**。 */
    @Test
    void save_sameId_updatesInPlace_notDuplicated() {
        repository.save("adai", task("dtask_1", "running", null));
        repository.save("adai", task("dtask_1", "done", "央行2025Q4货币政策报告要点"));

        List<LearnDigestTask> tasks = repository.findRecent("adai", 10);
        assertEquals(1, tasks.size(), "同 id 覆盖，不留重复行");
        assertEquals("done", tasks.get(0).status());
        assertEquals("央行2025Q4货币政策报告要点", tasks.get(0).title());
    }

    @Test
    void findRecent_isNewestFirst() {
        repository.save("adai", task("dtask_1", "done", "第一条"));
        repository.save("adai", task("dtask_2", "done", "第二条"));

        List<LearnDigestTask> tasks = repository.findRecent("adai", 10);
        assertEquals(List.of("dtask_2", "dtask_1"), tasks.stream().map(LearnDigestTask::id).toList());
    }

    @Test
    void save_beyondWindow_keepsNewestOnly() {
        for (int i = 0; i < 55; i++) {
            repository.save("adai", task("dtask_" + i, "done", "第 " + i + " 条"));
        }

        List<LearnDigestTask> tasks = repository.findRecent("adai", 100);
        assertEquals(50, tasks.size(), "滚动窗口封顶 50 条，文件不会无限增长");
        assertEquals("dtask_54", tasks.get(0).id(), "留下的必须是最新的那批");
    }

    @Test
    void findRecent_limitRespected_andOtherUsersIsolated() {
        repository.save("adai", task("dtask_1", "done", "A"));
        repository.save("bob", task("dtask_9", "done", "B"));

        assertEquals(1, repository.findRecent("adai", 1).size());
        assertEquals("dtask_1", repository.findRecent("adai", 10).get(0).id(), "不同用户互不串账");
    }

    /** 文件损坏 → 按暂无记录处理（fail-open）：追踪坏了不该让「看清单」这件事直接 500。 */
    @Test
    void corruptedFile_returnsEmptyInsteadOfThrowing() {
        storage.write("adai", "learn/_digest-tasks.json", "{ 这不是 json");

        assertDoesNotThrow(() -> repository.findRecent("adai", 10));
        assertTrue(repository.findRecent("adai", 10).isEmpty());
    }

    /**
     * 写盘失败**不得抛出**——这是本仓储与配额账本（fail-closed）刻意相反的取舍：
     * 记不上账最多是「这条查不到踪迹」，而把已经落盘的整理报成失败才是真事故。
     */
    @Test
    void writeFailure_doesNotThrow_failOpen() {
        FileStorage broken = new InMemoryFileStorage() {
            @Override
            public void write(String userId, String path, String content) {
                throw new RuntimeException("disk full");
            }
        };
        LearnDigestTaskFileRepository r = new LearnDigestTaskFileRepository(broken);

        assertDoesNotThrow(() -> r.save("adai", task("dtask_1", "done", "央行报告")));
    }
}
