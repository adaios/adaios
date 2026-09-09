package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.kernel.ai.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LearnDigestAppServiceTest — learn 消化用例（RFC 20260829，V1 + 2026-09-10 提交式）。
 * <p>
 * 覆盖：AI 成功卡片化（trading type → trade_related 落盘）、非 trading 强制 false、
 * type hint 显式覆盖、LLM 抛异常 → 素材留存 + 400（fail-visible 不落半成品）、
 * 输出不可解析 → 素材留存 + 400、title 空 → 素材留存 + 400、type 越界回落 other；
 * 提交式（V1.1）：受理 → 后台消化 → job done/failed、inflight 去重不重复烧 AI、
 * 执行器拒绝 → 400 人话、无任务 → idle。
 */
class LearnDigestAppServiceTest {

    private final AiClient aiClient = mock(AiClient.class);
    private final LearnCardRepository repository = mock(LearnCardRepository.class);
    private final List<Runnable> submitted = new ArrayList<>();
    private LearnDigestAppService service;

    /** 捕获型执行器：submit 只入队不执行，runAll() 手动放行（测时序/inflight 去重）。 */
    private final Executor capturingExecutor = submitted::add;

    /** 直执行器：submit 同步跑完后台任务（测受理即完成路径）。 */
    private final Executor directExecutor = Runnable::run;

    private void runAll() {
        List<Runnable> copy = List.copyOf(submitted);
        submitted.clear();
        copy.forEach(Runnable::run);
    }

    @BeforeEach
    void setUp() {
        service = new LearnDigestAppService(aiClient, repository, capturingExecutor);
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
                "观点", List.of("要点"), List.of(), "");
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
                "观点", List.of("要点"), List.of(), "");
        when(repository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(card));
        when(repository.tree("adai")).thenReturn(java.util.Map.of("ai", List.of(card)));
        assertEquals(1, service.list("adai", LearnCard.TYPE_AI).size());
        assertEquals(0, service.list("adai", "bogus").size(), "非法 type 返回空");
        assertEquals(1, service.tree("adai").get("ai").size());
        verify(repository).list("adai", LearnCard.TYPE_AI);
        verify(repository).tree("adai");
    }

    // ── V2 复习状态流转 ──

    @Test
    void changeStatus_valid_delegatesAndReturnsUpdated() {
        LearnCard updated = new LearnCard(LearnCard.TYPE_AI, "某卡", null, null, null, null,
                LocalDate.now(), LearnCard.STATUS_REVIEW, false, null, List.of(),
                "观点", List.of("要点"), List.of(), "");
        when(repository.updateStatus(eq("adai"), eq(LearnCard.TYPE_AI), eq("某卡"), eq(LearnCard.STATUS_REVIEW), any()))
                .thenReturn(updated);
        LearnCard result = service.changeStatus("adai", LearnCard.TYPE_AI, "某卡", LearnCard.STATUS_REVIEW);
        assertEquals(LearnCard.STATUS_REVIEW, result.status());
        verify(repository).updateStatus(eq("adai"), eq(LearnCard.TYPE_AI), eq("某卡"), eq(LearnCard.STATUS_REVIEW), any());
    }

    @Test
    void changeStatus_invalidType_throwsBeforeRepository() {
        assertThrows(LearnException.class,
                () -> service.changeStatus("adai", "hacking", "卡", LearnCard.STATUS_REVIEW));
        verify(repository, never()).updateStatus(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void changeStatus_invalidStatus_throwsBeforeRepository() {
        assertThrows(LearnException.class,
                () -> service.changeStatus("adai", LearnCard.TYPE_AI, "卡", "archived"));
        verify(repository, never()).updateStatus(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void changeStatus_blankTitle_throwsBeforeRepository() {
        assertThrows(LearnException.class,
                () -> service.changeStatus("adai", LearnCard.TYPE_AI, "  ", LearnCard.STATUS_DONE));
        verify(repository, never()).updateStatus(anyString(), anyString(), anyString(), anyString(), any());
    }

    // ── V2 编辑（P2-learn6/7 修复后：merge 在仓储锁内，service 校验参数并委托）──

    @Test
    void edit_delegatesPatchToRepository() {
        LearnCard merged = new LearnCard(LearnCard.TYPE_AI, "某卡", null, null, null, null,
                LocalDate.now(), LearnCard.STATUS_NEW, false, null, List.of("rag", "新标签"),
                "新观点", List.of("旧要点"), List.of("新疑问"), "复述内容");
        when(repository.applyEdit(eq("adai"), eq(LearnCard.TYPE_AI), eq("某卡"), any()))
                .thenReturn(merged);

        LearnCard result = service.edit("adai", LearnCard.TYPE_AI, "某卡",
                new com.adaiadai.core.domain.learn.LearnCardPatch("新观点", null, List.of("新疑问"),
                        "复述内容", null, null, List.of("rag", "新标签")));

        assertEquals("新观点", result.coreView());
        assertEquals("复述内容", result.retell());
        // 补丁原样下传（仓储锁内 merge，见 LearnCardFileRepositoryTest.applyEdit*）
        verify(repository).applyEdit(eq("adai"), eq(LearnCard.TYPE_AI), eq("某卡"),
                argThat(p2 -> "新观点".equals(p2.coreView())
                        && p2.keyPoints() == null
                        && java.util.List.of("新疑问").equals(p2.questions())
                        && "复述内容".equals(p2.retell())
                        && java.util.List.of("rag", "新标签").equals(p2.tags())));
    }

    @Test
    void edit_missingCard_throwsFromRepository() {
        when(repository.applyEdit(eq("adai"), eq(LearnCard.TYPE_AI), eq("没有"), any()))
                .thenThrow(new LearnException("卡片不存在：ai/没有"));
        LearnException e = assertThrows(LearnException.class,
                () -> service.edit("adai", LearnCard.TYPE_AI, "没有",
                        new com.adaiadai.core.domain.learn.LearnCardPatch(null, null, null, null, null, null, null)));
        assertTrue(e.getMessage().contains("卡片不存在"));
        verify(repository).applyEdit(eq("adai"), eq(LearnCard.TYPE_AI), eq("没有"), any());
    }

    @Test
    void edit_invalidTypeOrBlankTitle_throwsBeforeRepository() {
        assertThrows(LearnException.class,
                () -> service.edit("adai", "bogus", "卡",
                        new com.adaiadai.core.domain.learn.LearnCardPatch(null, null, null, null, null, null, null)));
        assertThrows(LearnException.class,
                () -> service.edit("adai", LearnCard.TYPE_AI, "  ",
                        new com.adaiadai.core.domain.learn.LearnCardPatch(null, null, null, null, null, null, null)));
        verify(repository, never()).applyEdit(anyString(), anyString(), anyString(), any());
    }

    @Test
    void changeStatus_illegalStatus_throwsBeforeRepository() {
        assertThrows(LearnException.class,
                () -> service.changeStatus("adai", LearnCard.TYPE_AI, "某卡", "archived"));
        verify(repository, never()).updateStatus(anyString(), anyString(), anyString(), anyString(), any());
    }

    // ── 提交式消化（2026-09-10 learn 喂入入口批，对齐复盘 submitReview）──

    @Test
    void submit_accepted_thenBackgroundDigest_jobDone() {
        LearnDigestAppService direct = new LearnDigestAppService(aiClient, repository, directExecutor);
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);

        LearnDigestAppService.DigestSubmitResult result =
                direct.submit("adai", "字幕内容", null, "bilibili", "某UP", "https://b23.tv/x", "2026-05-05");

        assertEquals(LearnDigestAppService.STATUS_PENDING, result.status());
        LearnDigestAppService.DigestJobStatus job = direct.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_DONE, job.status());
        assertEquals(LearnCard.TYPE_TRADING, job.type());
        assertEquals("回调一半的判定", job.title());
        verify(repository).save(eq("adai"), any(LearnCard.class));
        verify(repository, never()).saveRawSource(anyString(), anyString());
    }

    @Test
    void submit_inflight_returnsRunningWithoutSecondAiCall() {
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);

        LearnDigestAppService.DigestSubmitResult first =
                service.submit("adai", "字幕内容", null, null, null, null, null);
        assertEquals(LearnDigestAppService.STATUS_PENDING, first.status());
        assertEquals(LearnDigestAppService.STATUS_RUNNING, service.digestJobStatus("adai").status());

        // 在跑中二次提交 → running（不重复入队/不重复烧 AI）
        LearnDigestAppService.DigestSubmitResult second =
                service.submit("adai", "另一份素材", null, null, null, null, null);
        assertEquals(LearnDigestAppService.STATUS_RUNNING, second.status());
        assertEquals(1, submitted.size());

        runAll();
        LearnDigestAppService.DigestJobStatus job = service.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_DONE, job.status());
        verify(aiClient, times(1)).generate(any(), any());
        verify(repository, times(1)).save(eq("adai"), any(LearnCard.class));
    }

    @Test
    void submit_llmFailure_jobFailed_savesRawSourceNotHalfCard() {
        LearnDigestAppService direct = new LearnDigestAppService(aiClient, repository, directExecutor);
        when(aiClient.generate(any(), any())).thenThrow(new RuntimeException("llm down"));

        LearnDigestAppService.DigestSubmitResult result =
                direct.submit("adai", "字幕内容", null, null, null, null, null);
        assertEquals(LearnDigestAppService.STATUS_PENDING, result.status());

        LearnDigestAppService.DigestJobStatus job = direct.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_FAILED, job.status());
        assertTrue(job.message().contains("素材已留存"));
        verify(repository).saveRawSource(eq("adai"), anyString());
        verify(repository, never()).save(anyString(), any(LearnCard.class));
    }

    @Test
    void submit_rejectedByExecutor_throws400HumanMessage() {
        Executor rejecting = r -> {
            throw new RejectedExecutionException("full");
        };
        LearnDigestAppService svc = new LearnDigestAppService(aiClient, repository, rejecting);

        LearnException e = assertThrows(LearnException.class,
                () -> svc.submit("adai", "字幕内容", null, null, null, null, null));
        assertTrue(e.getMessage().contains("繁忙"));
        assertEquals(LearnDigestAppService.STATUS_IDLE, svc.digestJobStatus("adai").status());
        verify(repository, never()).save(anyString(), any(LearnCard.class));
    }

    @Test
    void submit_blankContent_throwsBeforeAccept() {
        LearnException e = assertThrows(LearnException.class,
                () -> service.submit("adai", "   ", null, null, null, null, null));
        assertTrue(e.getMessage().contains("不能为空"));
        assertEquals(0, submitted.size());
        assertEquals(LearnDigestAppService.STATUS_IDLE, service.digestJobStatus("adai").status());
    }

    @Test
    void submit_invalidTypeHint_throwsBeforeAccept() {
        assertThrows(LearnException.class,
                () -> service.submit("adai", "字幕内容", "hacking", null, null, null, null));
        assertEquals(0, submitted.size());
    }

    @Test
    void digestJobStatus_idleWhenNoJob_doneStableUntilConsumed() {
        assertEquals(LearnDigestAppService.STATUS_IDLE, service.digestJobStatus("adai").status());
        // 完成结果超过 TTL 后惰性清理回 idle（模拟 60s 未消费）
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);
        LearnDigestAppService direct = new LearnDigestAppService(aiClient, repository, directExecutor);
        direct.submit("adai", "字幕内容", null, null, null, null, null);
        assertEquals(LearnDigestAppService.STATUS_DONE, direct.digestJobStatus("adai").status());
        // TTL 边界不可注入时钟，此处仅验证同一 job 重复查询幂等（done 保留至消费/覆盖）
        assertEquals(LearnDigestAppService.STATUS_DONE, direct.digestJobStatus("adai").status());
    }
}
