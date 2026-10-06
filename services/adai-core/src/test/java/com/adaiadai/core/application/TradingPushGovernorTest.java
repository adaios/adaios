package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.MarketPushEvent;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.kernel.push.PushChannel;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingPushGovernor — 推送日上限守卫单元测试（design-final §9#16 · ★V1：全局 ≤8/日、超限合并）。
 * 覆盖三条准入规则（放行 / 折叠 / 当日已满不推）与折叠条的内容/锁屏中性。
 */
class TradingPushGovernorTest {

    /** 计数源带 used 条当日推送的守卫（计数源 = 当日已落盘条数）。 */
    private static TradingPushGovernor governorWithUsed(int used) {
        MarketPushRepository repo = mock(MarketPushRepository.class);
        List<MarketPushEvent> events = new ArrayList<>();
        for (int i = 0; i < used; i++) {
            events.add(new MarketPushEvent("push_" + i, "600519", "贵州茅台", "msg", "loss", "10:00"));
        }
        when(repo.findByDate(anyString(), any())).thenReturn(events);
        return new TradingPushGovernor(repo);
    }

    /** 一批含标的/价格（含锁屏版）的推送样本。 */
    private static List<PushChannel.PushMessage> batch(int n) {
        List<PushChannel.PushMessage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new PushChannel.PushMessage("T" + i, "📉 持仓提醒 " + i, "loss", "60050" + i,
                    "票" + i, LocalTime.of(10, 0), "有持仓出现异动。打开阿呆看看。", "行情提醒"));
        }
        return list;
    }

    @Test
    void admit_emptyBatch_returnsEmpty() {
        assertTrue(governorWithUsed(0).admit("adai", List.of()).isEmpty());
        assertTrue(governorWithUsed(0).admit("adai", null).isEmpty());
    }

    /** 规则 2：used+batch ≤ 8 → 原样放行（同一批）。 */
    @Test
    void admit_withinLimit_passesThroughUnchanged() {
        List<PushChannel.PushMessage> b = batch(2);
        assertSame(b, governorWithUsed(0).admit("adai", b));
    }

    /** 边界：6+2=8 恰好到上限 → 仍放行（上限是「不超过 8」）。 */
    @Test
    void admit_exactlyAtLimit_passesThrough() {
        List<PushChannel.PushMessage> b = batch(2);
        assertSame(b, governorWithUsed(6).admit("adai", b));
    }

    /** 规则 3：7+2=9>8 → 折叠为一条汇总（不丢弃：正文保留各条原文）。 */
    @Test
    void admit_wouldExceed_foldsBatchIntoOneDigest() {
        List<PushChannel.PushMessage> admitted = governorWithUsed(7).admit("adai", batch(2));

        assertEquals(1, admitted.size());
        PushChannel.PushMessage m = admitted.get(0);
        assertEquals(TradingPushGovernor.MERGED_TYPE, m.type());
        assertEquals("今日提醒汇总", m.title());
        assertTrue(m.content().contains("今天还有 2 条提醒"), m.content());
        assertTrue(m.content().contains("📉 持仓提醒 0") && m.content().contains("📉 持仓提醒 1"),
                "不丢弃：正文保留各条原文，实际: " + m.content());
        // 汇总条跨多个标的 → symbol 置空（PushMessage.deepLink 回落 trading:today）
        assertNull(m.symbol());
        // 锁屏中性：不带标的/价格
        assertFalse(m.notificationContent().contains("持仓提醒 0"));
        assertFalse(m.notificationContent().contains("票0"));
    }

    /** 规则 1：当日已满 8 条 → 本批不推（空列表，由调用方决定不记签名）。 */
    @Test
    void admit_atLimit_suppressesBatch() {
        assertTrue(governorWithUsed(TradingPushGovernor.DAILY_LIMIT).admit("adai", batch(1)).isEmpty());
    }

    /** 单条调用（定时推送）永不折叠：used=7 + 1 条 → 原样放行；used=8 + 1 条 → 不推。 */
    @Test
    void admit_singleMessage_neverFolds() {
        List<PushChannel.PushMessage> one = batch(1);
        assertSame(one, governorWithUsed(7).admit("adai", one), "单条 7+1=8 应原样放行");
        assertTrue(governorWithUsed(8).admit("adai", one).isEmpty(), "单条在满额时整条不推");
    }
}
