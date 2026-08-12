package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RecordRetryService — 定时重补（REVIEW #227 过滤禁用账号）。
 * 覆盖：只遍历 enabled 账号 / 无启用账号跳过不再 fallback default / 禁用账号不烧 AI。
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
                mock(MemoryService.class), cards, accounts);

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
                mock(MemoryService.class), cards, accounts);

        svc.retryUnprocessed();

        verify(records, never()).findAll(anyString());
        verify(cards, never()).findAll(anyString());
        verify(ai, never()).understand(any());
    }
}
