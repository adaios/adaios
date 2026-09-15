package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnPage;
import com.adaiadai.core.kernel.ai.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LearnRepagesTest — 历史卡回填页序列（2026-09-15 卡片流批）。
 * <p>
 * 底线：**只补呈现层**（页段），不重写用户读过的正文；没素材/没排出页/只读卡都要给人话，
 * 且**任何一条失败路径都不许写盘**。
 */
class LearnRepagesTest {

    private LearnCardRepository repository;
    private AiClient aiClient;
    private LearnDigestAppService service;

    @BeforeEach
    void setUp() {
        repository = mock(LearnCardRepository.class);
        aiClient = mock(AiClient.class);
        Executor executor = Runnable::run;
        service = new LearnDigestAppService(aiClient, repository, executor,
                mock(LearnFetchService.class), mock(LearnTranscriptionService.class));
    }

    private LearnCard card(boolean writable) {
        return new LearnCard("ai", "老卡", "bilibili", "某UP", "https://b23.tv/x", "2026-05-05",
                LocalDate.of(2026, 9, 13), LearnCard.STATUS_NEW, false, null,
                List.of("harness"), "已有核心观点", List.of("已有的要点"), List.of("已有的疑问"), "")
                .withWritable(writable);
    }

    private void givenCardAndMaterial(LearnCard c) {
        when(repository.find("adai", "ai", "老卡")).thenReturn(Optional.of(c));
        when(repository.rawAssets("adai", "ai", c.topic())).thenReturn(List.of("bilibili-BV1-transcript.txt"));
        when(repository.readRaw("adai", "bilibili-BV1-transcript.txt")).thenReturn("这是原始字幕内容……");
    }

    @Test
    void repages_writesPagesAndNeverTouchesBody() {
        givenCardAndMaterial(card(true));
        when(aiClient.generate(any(), anyString())).thenReturn("""
                [{"kind":"diagram","title":"三层递进","claim":"范围变大",
                  "nodes":[{"text":"Prompt Engineering","note":"怎么问"}]},
                 {"kind":"numbers","title":"账单","claim":"贵 20 倍","numbers":[{"v":"6 小时","l":"$200"}]}]
                """);

        service.repages("adai", "ai", "老卡");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LearnPage>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).updatePages(eq("adai"), eq("ai"), eq("老卡"), captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals(LearnPage.KIND_DIAGRAM, captor.getValue().get(0).kind());
        // 只补页段：不得走 save/update 这类会重写正文的路径
        verify(repository, never()).save(anyString(), any(LearnCard.class));
        verify(repository, never()).update(anyString(), any(LearnCard.class));
    }

    @Test
    void repages_readOnlyCard_toldInPlainWords_andNothingWritten() {
        givenCardAndMaterial(card(false));

        LearnException ex = assertThrows(LearnException.class, () -> service.repages("adai", "ai", "老卡"));
        assertTrue(ex.getMessage().contains("只当资料看"), ex.getMessage());
        verify(repository, never()).updatePages(anyString(), anyString(), anyString(), any());
    }

    @Test
    void repages_withoutRawMaterial_failsVisible() {
        LearnCard c = card(true);
        when(repository.find("adai", "ai", "老卡")).thenReturn(Optional.of(c));
        when(repository.rawAssets("adai", "ai", c.topic())).thenReturn(List.of());

        LearnException ex = assertThrows(LearnException.class, () -> service.repages("adai", "ai", "老卡"));
        assertTrue(ex.getMessage().contains("没留下原始素材"), ex.getMessage());
        verify(aiClient, never()).generate(any(), anyString());
    }

    @Test
    void repages_badAiOutput_failsVisible_andDoesNotWritePages() {
        givenCardAndMaterial(card(true));
        when(aiClient.generate(any(), anyString())).thenReturn("我这次不想输出 JSON");

        LearnException ex = assertThrows(LearnException.class, () -> service.repages("adai", "ai", "老卡"));
        assertTrue(ex.getMessage().contains("没排出可用的页"), ex.getMessage());
        verify(repository, never()).updatePages(anyString(), anyString(), anyString(), any());
    }

    @Test
    void repages_llmThrows_failsVisible() {
        givenCardAndMaterial(card(true));
        when(aiClient.generate(any(), anyString())).thenThrow(new RuntimeException("网络抖了一下"));

        LearnException ex = assertThrows(LearnException.class, () -> service.repages("adai", "ai", "老卡"));
        assertTrue(ex.getMessage().contains("可稍后重试"), ex.getMessage());
        verify(repository, never()).updatePages(anyString(), anyString(), anyString(), any());
    }

    @Test
    void repages_metaJsonAloneIsNotMaterial() {
        LearnCard c = card(true);
        when(repository.find("adai", "ai", "老卡")).thenReturn(Optional.of(c));
        when(repository.rawAssets("adai", "ai", c.topic())).thenReturn(List.of("bilibili-BV1-meta.json"));
        when(repository.readRaw("adai", "bilibili-BV1-meta.json")).thenReturn("{\"title\":\"x\"}");

        LearnException ex = assertThrows(LearnException.class, () -> service.repages("adai", "ai", "老卡"));
        assertTrue(ex.getMessage().contains("没留下原始素材"), "元数据不是底稿：" + ex.getMessage());
    }
}
