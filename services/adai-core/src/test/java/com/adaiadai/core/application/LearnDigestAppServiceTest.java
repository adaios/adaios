package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.kernel.ai.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LearnDigestAppServiceTest — learn 消化用例（RFC 20260829，V1）。
 * <p>
 * 覆盖：AI 成功卡片化（trading type → trade_related 落盘）、非 trading 强制 false、
 * type hint 显式覆盖、LLM 抛异常 → 素材留存 + 400（fail-visible 不落半成品）、
 * 输出不可解析 → 素材留存 + 400、title 空 → 素材留存 + 400、type 越界回落 other。
 */
class LearnDigestAppServiceTest {

    private final AiClient aiClient = mock(AiClient.class);
    private final LearnCardRepository repository = mock(LearnCardRepository.class);
    private LearnDigestAppService service;

    @BeforeEach
    void setUp() {
        service = new LearnDigestAppService(aiClient, repository);
    }

    private static final String TRADING_JSON = """
            {"title":"回调一半的判定","type":"trading","tags":["止损","回调"],
            "core_view":"回调到一半才是买点，几何口径 (high+low)/2",
            "key_points":["02:31 回调一半=(high+low)/2","05:47 与 R66 互补"],
            "questions":["它与课程口径一致吗？"],
            "trade_related":true,"trade_note":"与 R66 止损互补"}""";

    @Test
    void digest_tradingContent_savesCardWithTradeRelated() {
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);
        LearnCard card = service.digest("adai", "视频字幕……回调一半……",
                null, "bilibili", "某UP", "https://b23.tv/x", "2026-05-05");

        assertEquals("回调一半的判定", card.title());
        assertEquals(LearnCard.TYPE_TRADING, card.type());
        assertTrue(card.tradeRelated(), "trading 且含可执行规则 → trade_related=true");
        assertEquals("与 R66 止损互补", card.tradeNote());
        assertEquals(LocalDate.now(), card.created());
        assertEquals("bilibili", card.platform());
        assertEquals("https://b23.tv/x", card.url());
        verify(repository).save(anyString(), any(LearnCard.class));
        verify(repository, never()).saveRawSource(anyString(), anyString());
    }

    @Test
    void digest_aiContent_forceTradeRelatedFalse() {
        when(aiClient.generate(any(), any())).thenReturn("""
                {"title":"RAG 与 Agent 的区别","type":"ai","tags":["rag","agent"],
                "core_view":"RAG 是检索增强，Agent 是自主行动",
                "key_points":["RAG 适合知识问答","Agent 适合多步任务"],
                "questions":["两者何时结合？"],"trade_related":true,"trade_note":""}""");
        LearnCard card = service.digest("adai", "文章正文……", null, "web", null, null, null);
        assertEquals(LearnCard.TYPE_AI, card.type());
        assertFalse(card.tradeRelated(), "非 trading 内容即使 LLM 标 true 也强制 false");
    }

    @Test
    void digest_typeHint_overridesLlmType() {
        when(aiClient.generate(any(), any())).thenReturn("""
                {"title":"某视频","type":"other","tags":[],
                "core_view":"观点","key_points":[],"questions":[],
                "trade_related":false,"trade_note":""}""");
        LearnCard card = service.digest("adai", "交易视频字幕……", LearnCard.TYPE_TRADING,
                "bilibili", "UP", null, null);
        assertEquals(LearnCard.TYPE_TRADING, card.type(), "显式 type hint 覆盖 LLM 判定");
    }

    @Test
    void digest_invalidTypeHint_throws400() {
        LearnException e = assertThrows(LearnException.class,
                () -> service.digest("adai", "内容", "hacking", null, null, null, null));
        assertTrue(e.getMessage().contains("类型仅支持"));
        verify(repository, never()).save(anyString(), any());
    }

    @Test
    void digest_llmTypeOutOfEnum_fallsBackOther() {
        when(aiClient.generate(any(), any())).thenReturn("""
                {"title":"怪异内容","type":"hacking","tags":[],
                "core_view":"观点","key_points":[],"questions":[],
                "trade_related":false,"trade_note":""}""");
        LearnCard card = service.digest("adai", "内容", null, null, null, null, null);
        assertEquals(LearnCard.TYPE_OTHER, card.type(), "LLM type 越界回落 other（不落越界值）");
        assertFalse(card.tradeRelated());
    }

    @Test
    void digest_llmThrows_savesRawSourceAndThrows400() {
        when(aiClient.generate(any(), any())).thenThrow(new RuntimeException("DeepSeek timeout"));
        LearnException e = assertThrows(LearnException.class,
                () -> service.digest("adai", "原始字幕", null, null, null, null, null));
        assertTrue(e.getMessage().contains("原始素材已留存"), "素材不丢：消息应提示已留存");
        verify(repository).saveRawSource(anyString(), anyString());
        verify(repository, never()).save(anyString(), any());
    }

    @Test
    void digest_llmOutputUnparseable_savesRawSourceAndThrows400() {
        when(aiClient.generate(any(), any())).thenReturn("这不是 JSON，随便说点什么……");
        LearnException e = assertThrows(LearnException.class,
                () -> service.digest("adai", "字幕", null, null, null, null, null));
        assertTrue(e.getMessage().contains("无法识别"));
        verify(repository).saveRawSource(anyString(), anyString());
        verify(repository, never()).save(anyString(), any());
    }

    @Test
    void digest_emptyTitle_savesRawSourceAndThrows400() {
        when(aiClient.generate(any(), any())).thenReturn("""
                {"title":"  ","type":"ai","tags":[],
                "core_view":"观点","key_points":[],"questions":[],
                "trade_related":false,"trade_note":""}""");
        assertThrows(LearnException.class,
                () -> service.digest("adai", "字幕", null, null, null, null, null));
        verify(repository).saveRawSource(anyString(), anyString());
        verify(repository, never()).save(anyString(), any());
    }

    @Test
    void digest_blankContent_throws400WithoutAiCall() {
        LearnException e = assertThrows(LearnException.class,
                () -> service.digest("adai", "   ", null, null, null, null, null));
        assertTrue(e.getMessage().contains("素材内容不能为空"));
        verify(aiClient, never()).generate(any(), any());
        verify(repository, never()).save(anyString(), any());
    }

    @Test
    void detail_existing_returnsCard() {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "某卡", null, null, null, null,
                LocalDate.now(), LearnCard.STATUS_NEW, false, null, List.of(),
                "观点", List.of("要点"), List.of());
        when(repository.find("adai", LearnCard.TYPE_AI, "某卡")).thenReturn(java.util.Optional.of(card));
        assertEquals("某卡", service.detail("adai", LearnCard.TYPE_AI, "某卡").title());
    }

    @Test
    void detail_missing_throws400HumanMessage() {
        when(repository.find("adai", LearnCard.TYPE_AI, "不存在的卡")).thenReturn(java.util.Optional.empty());
        LearnException e = assertThrows(LearnException.class,
                () -> service.detail("adai", LearnCard.TYPE_AI, "不存在的卡"));
        assertTrue(e.getMessage().contains("卡片不存在"));
    }

    @Test
    void detail_invalidTypeOrBlankTitle_throws400() {
        assertThrows(LearnException.class, () -> service.detail("adai", "bogus", "x"));
        assertThrows(LearnException.class, () -> service.detail("adai", LearnCard.TYPE_AI, "  "));
    }

    @Test
    void list_tree_delegateToRepository() {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "卡", null, null, null, null,
                LocalDate.now(), LearnCard.STATUS_NEW, false, null, List.of(),
                "观点", List.of("要点"), List.of());
        when(repository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(card));
        when(repository.tree("adai")).thenReturn(java.util.Map.of("ai", List.of(card)));
        assertEquals(1, service.list("adai", LearnCard.TYPE_AI).size());
        assertEquals(0, service.list("adai", "bogus").size(), "非法 type 返回空");
        assertEquals(1, service.tree("adai").get("ai").size());
        verify(repository).list("adai", LearnCard.TYPE_AI);
        verify(repository).tree("adai");
    }
}
