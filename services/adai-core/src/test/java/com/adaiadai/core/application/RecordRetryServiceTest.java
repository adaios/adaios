package com.adaiadai.core.application;

import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RecordRetryService — 定时重补（REVIEW #227 过滤禁用账号；S-3 重补路径 domain 走 gateDomain）。
 * 覆盖：只遍历 enabled 账号 / 无启用账号跳过不再 fallback default / 禁用账号不烧 AI / 重补 domain 收敛。
 */
class RecordRetryServiceTest {

    @Test
    void noArg_onlyScansEnabledAccounts() {
        // accounts = [adai(enabled), bob(disabled)]：只扫 adai，bob 不扫（不烧 AI）
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(
                new Account("adai", "admin", true, null),
                new Account("bob", "user", false, null)));

        // 用真实 repository mock 验证遍历目标
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(anyString())).thenReturn(List.of());
        CardFileRepository cards = mock(CardFileRepository.class);
        when(cards.findAll(anyString())).thenReturn(List.of());
        AiClient ai = mock(AiClient.class);
        RecordRetryService svc = new RecordRetryService(
                records, mock(RecordUnderstandingService.class), ai,
                mock(MemoryService.class), cards, accounts, mock(PluginService.class));

        svc.retryUnprocessed();

        verify(records).findAll("adai");       // enabled 账号被扫描
        verify(cards).findAll("adai");
        verify(records, never()).findAll("bob"); // disabled 账号不扫描
        verify(cards, never()).findAll("bob");
        verify(ai, never()).understand(any());    // 无待补记录，不触发任何 AI
        verify(ai, never()).generate(any(), any());
    }

    @Test
    void noEnabledAccounts_skipsWithoutFallbackToDefault() {
        // #227：#212 后 default 已迁移移除，无启用账号时与行情轮询一致跳过，不再 fallback "default"
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(
                new Account("bob", "user", false, null)));

        RecordRepository records = mock(RecordRepository.class);
        CardFileRepository cards = mock(CardFileRepository.class);
        AiClient ai = mock(AiClient.class);
        RecordRetryService svc = new RecordRetryService(
                records, mock(RecordUnderstandingService.class), ai,
                mock(MemoryService.class), cards, accounts, mock(PluginService.class));

        svc.retryUnprocessed();

        verify(records, never()).findAll(anyString());
        verify(cards, never()).findAll(anyString());
        verify(ai, never()).understand(any());
    }

    @Test
    void retryRecord_domainGatedByPluginService() {
        // REVIEW S-3：重补路径与主路径同口径走 gateDomain——AI 判 trading、用户无插件 → 落盘 life。
        // 准备一条"未成功理解"的记录（summary 兜底 "recorded"，createdAt 早于 5 分钟 cutoff）
        ContentRecord pending = new ContentRecord(
                "rec_test", "note", "user", "标题", "今天买入立昂微 200 股",
                List.of(), LocalDateTime.now().minusMinutes(30), "log", "recorded", "life");

        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(
                new Account("alice", "user", true, null)));  // alice 无插件

        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll("alice")).thenReturn(List.of(pending));
        CardFileRepository cards = mock(CardFileRepository.class);
        when(cards.findAll(anyString())).thenReturn(List.of());

        RecordUnderstandingService understandingService = mock(RecordUnderstandingService.class);
        when(understandingService.composeAndUnderstand(eq("alice"), eq("note"), any(ContentRecord.class)))
                .thenReturn(new RecordUnderstandingService.UnderstandingResult(
                        new AiUnderstanding(
                                "买入立昂微", null, List.of(), List.of(),
                                List.of("交易"), "neutral", "trading", false, null, null),
                        null));

        PluginService pluginService = mock(PluginService.class);
        when(pluginService.gateDomain(eq("alice"), eq("trading"))).thenReturn("life");

        RecordRetryService svc = new RecordRetryService(
                records, understandingService, mock(AiClient.class),
                mock(MemoryService.class), cards, accounts, pluginService);

        svc.retryUnprocessed();

        // 落盘记录 domain 应为收敛后的 life（而非 AI 原始 trading）
        ArgumentCaptor<ContentRecord> saved = ArgumentCaptor.forClass(ContentRecord.class);
        verify(records).save(eq("alice"), saved.capture());
        assertEquals("life", saved.getValue().domain(), "无插件用户重补成功不得落盘 trading 标注");
        verify(pluginService).gateDomain("alice", "trading");
    }
}
