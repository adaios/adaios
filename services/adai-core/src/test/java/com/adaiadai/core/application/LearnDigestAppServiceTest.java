package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnDigestTask;
import com.adaiadai.core.domain.learn.LearnDigestTaskRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnPage;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.memory.MemoryPattern;
import com.adaiadai.core.kernel.memory.MemoryPreference;
import com.adaiadai.core.kernel.memory.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private final LearnFetchService fetchService = mock(LearnFetchService.class);
    private final LearnTranscriptionService transcriptionService = mock(LearnTranscriptionService.class);
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
        service = new LearnDigestAppService(aiClient, repository, capturingExecutor, fetchService, transcriptionService);
    }

    private static final String TRADING_JSON = """
            {"title":"回调一半的判定","type":"trading","tags":["止损","回调"],
            "core_view":"回调到一半才是买点，几何口径 (high+low)/2",
            "key_points":["02:31 回调一半=(high+low)/2","05:47 与 R66 互补"],
            "questions":["它与课程口径一致吗？"],
            "trade_related":true,"trade_note":"与 R66 止损互补"}""";

    // ── RFC 20260917 §四：画像回流（把「你是谁」拼进生成 prompt）──

    /** 带画像的装配（走主构造器：memoryService 落在生产参数位）。 */
    private LearnDigestAppService serviceWithProfile(MemoryService memoryService) {
        return new LearnDigestAppService(aiClient, repository, directExecutor, fetchService,
                transcriptionService, null, null, memoryService, 4096, 30);
    }

    /** 捕获最近一次交给 LLM 的 user prompt。 */
    private String capturePrompt() {
        ArgumentCaptor<com.adaiadai.core.kernel.context.engine.ContextPackage> captor =
                ArgumentCaptor.forClass(com.adaiadai.core.kernel.context.engine.ContextPackage.class);
        verify(aiClient).generate(captor.capture(), any());
        return captor.getValue().prompt();
    }

    /** V4 正向：有画像时偏好与行为模式都进 prompt。 */
    @Test
    void digest_withProfile_injectsPreferencesAndPatterns() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findAllPreferences("adai"))
                .thenReturn(List.of(new MemoryPreference("偏好系统化分析、要技术深度", 0.9)));
        when(memoryService.findAllPatterns("adai"))
                .thenReturn(List.of(new MemoryPattern("倾向先建立完整体系再执行", 0.8)));
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);

        serviceWithProfile(memoryService).digest("adai", "素材正文", null, "bilibili", "某UP", null, null);

        String prompt = capturePrompt();
        assertTrue(prompt.contains("偏好系统化分析"), "画像偏好应进入生成 prompt");
        assertTrue(prompt.contains("先建立完整体系"), "行为模式应进入生成 prompt");
        assertTrue(prompt.contains("我的长期画像"), "应有独立画像段（并明示只决定「怎么讲」）");
    }

    /** V4 反向：无画像（5 参数测试装配 memoryService=null）时 prompt 不含画像段——行为与改造前一致。 */
    @Test
    void digest_withoutProfile_promptHasNoProfileSection() {
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);

        service.digest("adai", "素材正文", null, "bilibili", "某UP", null, null);

        assertFalse(capturePrompt().contains("我的长期画像"), "无画像时不得出现画像段");
    }

    /** V5 验收：注入量有上限（Top 5），不随记忆增长而膨胀。 */
    @Test
    void digest_profileInjection_cappedAtTopN() {
        MemoryService memoryService = mock(MemoryService.class);
        List<MemoryPreference> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new MemoryPreference("偏好条目" + i, 0.9 - i * 0.01));
        }
        when(memoryService.findAllPreferences("adai")).thenReturn(many);
        when(memoryService.findAllPatterns("adai")).thenReturn(List.of());
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);

        serviceWithProfile(memoryService).digest("adai", "素材正文", null, null, null, null, null);

        String prompt = capturePrompt();
        long injected = 0;
        for (int i = 0; i < 20; i++) {
            if (prompt.contains("偏好条目" + i)) injected++;
        }
        assertEquals(5, injected, "V5：画像注入必须有上限（Top 5）");
    }

    /** 画像读取失败不得影响消化主链路：按「无画像」继续（fail-visible，不抛、不编造）。 */
    @Test
    void digest_profileReadFailure_fallsBackToNoProfile() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findAllPreferences("adai")).thenThrow(new RuntimeException("memory 文件坏了"));
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);

        LearnCard card = serviceWithProfile(memoryService)
                .digest("adai", "素材正文", null, null, null, null, null);

        assertEquals("回调一半的判定", card.title());
        assertFalse(capturePrompt().contains("我的长期画像"));
    }

    // ── RFC 20260917 §五 2b：产物反馈 → 沉淀为偏好（画像回流的输入端）──

    private LearnCard feedbackCard() {
        return new LearnCard(LearnCard.TYPE_AI, "某张卡", "bilibili", "某UP",
                "https://b23.tv/x", "2026-05-05", LocalDate.of(2026, 9, 6),
                LearnCard.STATUS_NEW, false, "", List.of(), "核心观点",
                List.of("要点"), List.of(), "");
    }

    /** 反馈写成 preference 记忆——可被 findAllPreferences 聚合，从而经画像回流作用于下一次生成。 */
    @Test
    void feedback_persistsPreference() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findAllPreferences("adai")).thenReturn(List.of());
        when(repository.find("adai", LearnCard.TYPE_AI, "某张卡"))
                .thenReturn(java.util.Optional.of(feedbackCard()));

        LearnDigestAppService.LearnFeedbackResult result = serviceWithProfile(memoryService)
                .feedback("adai", LearnCard.TYPE_AI, "某张卡", "太啰嗦了");

        assertEquals("recorded", result.status());
        ArgumentCaptor<com.adaiadai.core.kernel.memory.Memory> captor =
                ArgumentCaptor.forClass(com.adaiadai.core.kernel.memory.Memory.class);
        verify(memoryService).persist(eq("adai"), captor.capture());
        var memory = captor.getValue();
        assertEquals(com.adaiadai.core.kernel.memory.Memory.KIND_PREFERENCE, memory.kind(),
                "反馈必须以 preference 沉淀（否则进不了画像回流）");
        assertEquals(1, memory.preferences().size());
        assertEquals("太啰嗦了", memory.preferences().get(0).content());
    }

    /** 幂等：同一句话不重复沉淀（画像回流只取 Top 5，单句刷屏会把真偏好挤掉）。 */
    @Test
    void feedback_duplicate_isIdempotent() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findAllPreferences("adai"))
                .thenReturn(List.of(new MemoryPreference("太啰嗦了", 0.9)));
        when(repository.find("adai", LearnCard.TYPE_AI, "某张卡"))
                .thenReturn(java.util.Optional.of(feedbackCard()));

        LearnDigestAppService.LearnFeedbackResult result = serviceWithProfile(memoryService)
                .feedback("adai", LearnCard.TYPE_AI, "某张卡", "太啰嗦了");

        assertEquals("exists", result.status());
        verify(memoryService, never()).persist(anyString(), any());
    }

    /** 空反馈 → 人话拒绝，不落一条空偏好。 */
    @Test
    void feedback_blank_throwsHumanMessage() {
        LearnException e = assertThrows(LearnException.class,
                () -> service.feedback("adai", LearnCard.TYPE_AI, "某张卡", "   "));
        assertTrue(e.getMessage().contains("太啰嗦"), "应给可照做的引导：" + e.getMessage());
    }

    /** 卡片不存在 → 拒绝（不给不存在的卡留反馈，防脏数据）。 */
    @Test
    void feedback_cardNotFound_throws() {
        MemoryService memoryService = mock(MemoryService.class);
        when(repository.find("adai", LearnCard.TYPE_AI, "没有这张"))
                .thenReturn(java.util.Optional.empty());

        assertThrows(LearnException.class, () -> serviceWithProfile(memoryService)
                .feedback("adai", LearnCard.TYPE_AI, "没有这张", "太啰嗦了"));
        verify(memoryService, never()).persist(anyString(), any());
    }

    @Test
    void digest_tradingContent_savesCardWithTradeRelated() {        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);
        LearnCard card = service.digest("adai", "视频字幕……回调一半……",
                null, "bilibili", "某UP", "https://b23.tv/x", "2026-05-05");

        assertEquals("回调一半的判定", card.title());
        assertEquals(LearnCard.TYPE_TRADING, card.type());
        assertTrue(card.tradeRelated(), "trading 且含可执行规则 → trade_related=true");
        assertEquals("与 R66 止损互补", card.tradeNote());
        assertEquals(LocalDate.now(), card.created());
        assertEquals("bilibili", card.platform());
        assertEquals("https://b23.tv/x", card.url());
        verify(repository).save(anyString(), any(LearnCard.class), anyList());
        verify(repository, never()).saveRawSource(anyString(), anyString());
    }

    /** 新格式：消化时 LLM 一并给出页序列（2026-09-15 卡片流批）。 */
    private static final String PAGED_JSON = """
            {"title":"带页的卡","type":"ai","tags":["harness"],
            "core_view":"一句话观点",
            "key_points":["要点一"],
            "questions":["疑问一"],
            "trade_related":false,"trade_note":"",
            "pages":[{"kind":"diagram","title":"三层递进","claim":"范围变大",
                      "nodes":[{"text":"Prompt Engineering","note":"怎么问"}]},
                     {"kind":"numbers","title":"账单","claim":"贵 20 倍",
                      "numbers":[{"v":"6 小时","l":"$200"}]}]}""";

    @Test
    void digest_withPages_handsPagesToStorage() {
        when(aiClient.generate(any(), any())).thenReturn(PAGED_JSON);
        service.digest("adai", "素材正文", null, "bilibili", "某UP", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LearnPage>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).save(eq("adai"), any(LearnCard.class), captor.capture());
        assertEquals(2, captor.getValue().size(), "页序列必须一路传到落盘");
        assertEquals(LearnPage.KIND_DIAGRAM, captor.getValue().get(0).kind());
        assertEquals("$200", captor.getValue().get(1).numbers().get(0).l());
    }

    @Test
    void digest_withoutPages_storesEmptyPages() {
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);
        service.digest("adai", "素材正文", null, "bilibili", "某UP", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LearnPage>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).save(anyString(), any(LearnCard.class), captor.capture());
        assertTrue(captor.getValue().isEmpty(), "LLM 没给页 → 空列表 → 前端退回旧形态（不报错）");
    }

    @Test
    void digest_brokenPages_doesNotFailCard() {
        // pages 字段坏掉（这里是类型不对）时，卡片本身必须照常落盘——呈现层问题不该毁掉知识资产
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON.replace(
                "\"trade_note\":\"与 R66 止损互补\"}",
                "\"trade_note\":\"与 R66 止损互补\",\"pages\":\"这不是数组\"}"));
        LearnCard card = service.digest("adai", "素材正文", null, "bilibili", "某UP", null, null);

        assertEquals("回调一半的判定", card.title());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LearnPage>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).save(anyString(), any(LearnCard.class), captor.capture());
        assertTrue(captor.getValue().isEmpty(), "坏页 → 空页，不抛异常");
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
        verify(repository, never()).save(anyString(), any(), anyList());
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
        verify(repository, never()).save(anyString(), any(), anyList());
    }

    @Test
    void digest_llmOutputUnparseable_savesRawSourceAndThrows400() {
        when(aiClient.generate(any(), any())).thenReturn("这不是 JSON，随便说点什么……");
        LearnException e = assertThrows(LearnException.class,
                () -> service.digest("adai", "字幕", null, null, null, null, null));
        assertTrue(e.getMessage().contains("无法识别"));
        verify(repository).saveRawSource(anyString(), anyString());
        verify(repository, never()).save(anyString(), any(), anyList());
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
        verify(repository, never()).save(anyString(), any(), anyList());
    }

    @Test
    void digest_blankContent_throws400WithoutAiCall() {
        LearnException e = assertThrows(LearnException.class,
                () -> service.digest("adai", "   ", null, null, null, null, null));
        assertTrue(e.getMessage().contains("素材内容不能为空"));
        verify(aiClient, never()).generate(any(), any());
        verify(repository, never()).save(anyString(), any(), anyList());
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
        LearnDigestAppService direct = new LearnDigestAppService(aiClient, repository, directExecutor, fetchService, transcriptionService);
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);

        LearnDigestAppService.DigestSubmitResult result =
                direct.submit("adai", "字幕内容", null, "bilibili", "某UP", "https://b23.tv/x", "2026-05-05");

        assertEquals(LearnDigestAppService.STATUS_PENDING, result.status());
        LearnDigestAppService.DigestJobStatus job = direct.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_DONE, job.status());
        assertEquals(LearnCard.TYPE_TRADING, job.type());
        assertEquals("回调一半的判定", job.title());
        verify(repository).save(eq("adai"), any(LearnCard.class), anyList());
        verify(repository, never()).saveRawSource(anyString(), anyString());
    }

    @Test
    void submit_inflight_returnsNotQueuedWithoutSecondAiCall() {
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);

        LearnDigestAppService.DigestSubmitResult first =
                service.submit("adai", "字幕内容", null, null, null, null, null);
        assertEquals(LearnDigestAppService.STATUS_PENDING, first.status());
        assertEquals(LearnDigestAppService.STATUS_RUNNING, service.digestJobStatus("adai").status());

        // 在跑中二次提交 → P2-分享4（2026-10-05）：如实回「没排上」（not_queued），
        // 不再复用它条状态说 running —— 那让分享扩展/喂入弹窗分不清「在跑」与「被丢了」。
        LearnDigestAppService.DigestSubmitResult second =
                service.submit("adai", "另一份素材", null, null, null, null, null);
        assertEquals(LearnDigestAppService.STATUS_NOT_QUEUED, second.status());
        assertNotNull(second.message(), "「没排上」要带一句人话（前端直接展示）");
        assertTrue(second.message().contains("没排上"), second.message());
        assertEquals(1, submitted.size(), "第二条不入队（刻意不做队列）");

        runAll();
        LearnDigestAppService.DigestJobStatus job = service.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_DONE, job.status());
        verify(aiClient, times(1)).generate(any(), any());
        verify(repository, times(1)).save(eq("adai"), any(LearnCard.class), anyList());
    }

    // ── 2026-09-23 分享回执批：同一链接不重复消化 ──
    // 背景：分享扩展提交后 1 秒就关窗、主 App 全程不被拉起，用户看不到「结果在哪」时会再分享一次
    // （2026-09-23 实测微博同一条连分享两次，落出 02/03 两张同源卡）。去重命中不抓取、不烧 AI。

    /** 已整理过的卡片（url 用微博分享短链的真实形态）。 */
    private static LearnCard cardWithUrl(String url) {
        return new LearnCard("ai", "AI 让 React Native 类中间层被判死刑", "weibo", "ruanyf", url,
                "2026-09-18", LocalDate.of(2026, 9, 23), "new", false, null, List.of(),
                "核心观点", List.of("要点"), List.of("疑问"), null, null, null, "ai辅助软件开发", true);
    }

    @Test
    void submit_sameLinkTwice_secondReturnsDoneWithoutSecondAiCall() {
        String link = "https://mapp.api.weibo.cn/fx/670488fd1c2f0e18b93434bb8aaed54e.html";
        when(repository.tree("adai")).thenReturn(Map.of("ai", List.of(cardWithUrl(link))));

        LearnDigestAppService.DigestSubmitResult result = service.submit("adai",
                new LearnDigestAppService.DigestRequest(link, null, null, null, null, null));

        assertEquals(LearnDigestAppService.STATUS_DONE, result.status(), "已整理过 → 直接回 done，不再排第二个任务");
        assertEquals(0, submitted.size(), "去重命中不得入队——既不抓取也不烧 AI");
        verifyNoInteractions(aiClient);

        // 关键：回执要带得出「哪一条整理好了」——App 学习页的 done 提示条就吃 title/topic
        LearnDigestAppService.DigestJobStatus job = service.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_DONE, job.status());
        assertEquals("AI 让 React Native 类中间层被判死刑", job.title());
        assertEquals("ai辅助软件开发", job.topic());
    }

    @Test
    void submit_sameLinkWithTrailingSlash_stillMatches() {
        when(repository.tree("adai")).thenReturn(Map.of("ai",
                List.of(cardWithUrl("https://example.com/post/1"))));

        LearnDigestAppService.DigestSubmitResult result = service.submit("adai",
                new LearnDigestAppService.DigestRequest("https://example.com/post/1///", null, null, null, null, null));

        assertEquals(LearnDigestAppService.STATUS_DONE, result.status());
        assertEquals(0, submitted.size());
    }

    @Test
    void submit_newLink_isNotTreatedAsDuplicate() {
        when(repository.tree("adai")).thenReturn(Map.of("ai",
                List.of(cardWithUrl("https://example.com/other"))));

        LearnDigestAppService.DigestSubmitResult result = service.submit("adai",
                new LearnDigestAppService.DigestRequest("https://example.com/new", null, null, null, null, null));

        assertEquals(LearnDigestAppService.STATUS_PENDING, result.status());
        assertEquals(1, submitted.size(), "没整理过的链接照常入队");
    }

    /**
     * 素材路径（粘贴正文）**不参与去重**：正文没有可比的来源链接，而用户确实可能有意把同一段
     * 文本重整理一遍（换个类型/补充来源）。去重只认链接，避免把「有意重做」误判成「重复分享」。
     */
    @Test
    void submit_pastedMaterial_isNeverTreatedAsDuplicate() {
        when(repository.tree("adai")).thenReturn(Map.of("ai",
                List.of(cardWithUrl("https://example.com/post/1"))));

        LearnDigestAppService.DigestSubmitResult result =
                service.submit("adai", "同一段正文再整理一次", null, null, null, null, null);

        assertEquals(LearnDigestAppService.STATUS_PENDING, result.status());
        assertEquals(1, submitted.size());
    }

    /** 查重是省钱优化、不是提交的正确性前提：读卡片失败不能反过来把用户这次整理拦掉。 */
    @Test
    void submit_dedupLookupFailure_stillAcceptsNormally() {
        when(repository.tree("adai")).thenThrow(new RuntimeException("storage down"));

        LearnDigestAppService.DigestSubmitResult result = service.submit("adai",
                new LearnDigestAppService.DigestRequest("https://example.com/new", null, null, null, null, null));

        assertEquals(LearnDigestAppService.STATUS_PENDING, result.status());
        assertEquals(1, submitted.size());
    }

    @Test
    void normalizeUrl_stripsWhitespaceAndTrailingSlashes() {
        assertEquals("https://a.com/b", LearnDigestAppService.normalizeUrl("  https://a.com/b/  "));
        assertNull(LearnDigestAppService.normalizeUrl("   "));
        assertNull(LearnDigestAppService.normalizeUrl(null));
    }

    // ── 2026-09-23 分享追踪批：「我分享过什么、成了没有」要落成账（学习页清单 + Feed 回话都读它）──

    private final LearnDigestTaskRepository taskRepository = mock(LearnDigestTaskRepository.class);

    /** 带追踪账的装配（走 11 参主构造：digestTaskRepository 落在它自己的参数位）。 */
    private LearnDigestAppService serviceWithTasks(Executor executor) {
        return new LearnDigestAppService(aiClient, repository, executor, fetchService, transcriptionService,
                null, null, null, taskRepository, 4096, 30);
    }

    private LearnDigestTask lastRecordedTask() {
        ArgumentCaptor<LearnDigestTask> captor = ArgumentCaptor.forClass(LearnDigestTask.class);
        verify(taskRepository, atLeastOnce()).save(eq("adai"), captor.capture());
        return captor.getValue();
    }

    /** 受理即入账：「我分享了什么」在还没结果时就能查到（这一步正是用户抱怨看不到的窗口）。 */
    @Test
    void submit_recordsTaskAsRunningWithUrl() {
        LearnDigestAppService s = serviceWithTasks(capturingExecutor);
        s.submit("adai", new LearnDigestAppService.DigestRequest(
                "https://mp.weixin.qq.com/s/jsOBc6WCH", null, null, null, null, null));

        LearnDigestTask t = lastRecordedTask();
        assertEquals("running", t.status());
        assertEquals("https://mp.weixin.qq.com/s/jsOBc6WCH", t.url(), "清单里要认得出「我分享的是哪一篇」");
        assertNotNull(t.id(), "账要有钥匙（内存 job 过期后靠它对上号）");
        assertNotNull(t.submittedAt());
        assertTrue(t.inProgress());
    }

    @Test
    void done_recordsTaskWithCardTitleAndSettledAt() {
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);
        LearnDigestAppService s = serviceWithTasks(directExecutor);

        s.submit("adai", "字幕内容", null, null, null, null, null);

        LearnDigestTask t = lastRecordedTask();
        assertEquals("done", t.status());
        assertEquals("回调一半的判定", t.title(), "「哪一条读好了」必须指名道姓");
        assertEquals("trading", t.type());
        assertNotNull(t.settledAt());
        assertTrue(t.settled());
    }

    @Test
    void failure_recordsTaskWithHumanReason() {
        when(aiClient.generate(any(), any())).thenThrow(new RuntimeException("llm down"));
        LearnDigestAppService s = serviceWithTasks(directExecutor);

        s.submit("adai", "字幕内容", null, null, null, null, null);

        LearnDigestTask t = lastRecordedTask();
        assertEquals("failed", t.status());
        assertNotNull(t.message(), "失败原因要留在账上（事后还能看到「哪条没成、为什么」）");
        assertNotNull(t.settledAt());
    }

    /** 去重命中也是一条有结局的账（且只烧了零次模型）。 */
    @Test
    void duplicateLink_recordsDoneWithoutSecondAiCall() {
        String link = "https://mapp.api.weibo.cn/fx/670488fd1c2f0e18b93434bb8aaed54e.html";
        when(repository.tree("adai")).thenReturn(Map.of("ai", List.of(cardWithUrl(link))));
        LearnDigestAppService s = serviceWithTasks(capturingExecutor);

        assertEquals(LearnDigestAppService.STATUS_DONE, s.submit("adai",
                new LearnDigestAppService.DigestRequest(link, null, null, null, null, null)).status());

        LearnDigestTask t = lastRecordedTask();
        assertEquals("done", t.status());
        assertEquals("AI 让 React Native 类中间层被判死刑", t.title());
        verifyNoInteractions(aiClient);
    }

    /**
     * 抢占任务位失败（已有任务在跑）→ **如实入账「没排上」**（P2-分享4，2026-10-05）。
     *
     * <p>此前的口径是「没受理就不写账」，理由是怕写假账；但生产上用户看到的是「我明明分享了两条，
     * 学习页只有一条」——第二条从世界上消失。既然扩展当时已经会提示「没排上」，账上就得有这一条，
     * 否则事后翻学习页对不上。本用例钉住：两条分享 → 两条账，第二条 status=not_queued 且带人话。
     */
    @Test
    void inflightSecondSubmit_recordsNotQueuedTask() {
        LearnDigestAppService s = serviceWithTasks(capturingExecutor);

        s.submit("adai", "第一条素材", null, null, null, null, null);
        LearnDigestAppService.DigestSubmitResult second =
                s.submit("adai", "第二条素材", null, null, null, null, null);

        assertEquals(LearnDigestAppService.STATUS_NOT_QUEUED, second.status());
        verify(taskRepository, times(2)).save(eq("adai"), any(LearnDigestTask.class));
        LearnDigestTask notQueued = lastRecordedTask();
        assertEquals("not_queued", notQueued.status());
        assertNotNull(notQueued.settledAt(), "没排上也是一个结局");
        assertFalse(notQueued.inProgress(), "不能继续显示成「正在读」");
        assertTrue(notQueued.settled());
        assertTrue(notQueued.message().contains("没排上"), notQueued.message());
    }

    /** 上一条正卡在「等你拍板」时再分享 → 文案指出先回上一条（出路不同，话也要不同）。 */
    @Test
    void inflightSecondSubmit_whileAwaitingConfirm_saysPickFirst() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(
                new com.adaiadai.core.domain.learn.LearnSource(
                        "bilibili", "BV1xx411c7mD", "https://www.bilibili.com/video/BV1xx411c7mD",
                        "某视频", "某UP", "2026-05-05", null, true, "https://audio/x.m4s", 2244, List.of()));
        when(transcriptionService.unavailableReason()).thenReturn(null);
        when(transcriptionService.estimate(eq("adai"), any())).thenReturn(
                new LearnTranscriptionService.CostEstimate(2244, true, 0.18d, 0, 36000, 36000, false));
        LearnDigestAppService s = serviceWithTasks(directExecutor);

        s.submit("adai", new LearnDigestAppService.DigestRequest(
                "https://www.bilibili.com/video/BV1xx411c7mD", null, null, null, null, null));
        assertEquals(LearnDigestAppService.STATUS_NEEDS_CONFIRMATION, s.digestJobStatus("adai").status());

        LearnDigestAppService.DigestSubmitResult second =
                s.submit("adai", "另一份素材", null, null, null, null, null);

        assertEquals(LearnDigestAppService.STATUS_NOT_QUEUED, second.status());
        assertTrue(second.message().contains("拍板"), second.message());
        assertEquals("not_queued", lastRecordedTask().status());
    }

    // ── ① P2-learn34（2026-10-05）：决策入口过期 → 落盘账那条一并标 expired ──

    /** 账上一条「待确认」，而内存里已经没有对应入口（重启/被顶掉/TTL 已过）。 */
    private static LearnDigestTask stalePending() {
        return new LearnDigestTask("dtask_stale", "https://www.bilibili.com/video/BV1xx411c7mD",
                "杭州无人机外卖航线", "bilibili", "needs_confirmation", null, "预计约 0.00 元",
                null, null, null, "2026-09-25T05:13:00", null);
    }

    /**
     * 看整理清单（GET /digest/jobs）时，把已经没有决策入口的「待确认」如实标 expired——
     * 生产实据：2026-09-25 05:13 那条 B 站视频，21 小时后仍显示待确认，而入口早没了。
     */
    @Test
    void tasks_pendingConfirmWithoutLiveEntry_marksAccountExpired() {
        LearnDigestAppService s = serviceWithTasks(capturingExecutor);
        when(taskRepository.findRecent(eq("adai"), anyInt())).thenReturn(List.of(stalePending()));

        s.tasks("adai", 20);

        ArgumentCaptor<LearnDigestTask> captor = ArgumentCaptor.forClass(LearnDigestTask.class);
        verify(taskRepository).save(eq("adai"), captor.capture());
        LearnDigestTask saved = captor.getValue();
        assertEquals("expired", saved.status());
        assertEquals(LearnDigestAppService.EXPIRED_CONFIRM_MESSAGE, saved.message());
        assertNotNull(saved.settledAt(), "过期也是一个结局，账上要有结账时刻");
        assertFalse(saved.inProgress(), "不能再显示成「等用户拍板」");
        assertTrue(saved.settled());
    }

    /** 入口没了还硬点头 → 账如实标过期 + 人话说清「已经过期」，不说含糊的「没有任务」。 */
    @Test
    void confirm_whenEntryGone_marksAccountExpiredAndSaysExpired() {
        LearnDigestAppService s = serviceWithTasks(capturingExecutor);
        when(taskRepository.findRecent(eq("adai"), anyInt())).thenReturn(List.of(stalePending()));

        LearnException e = assertThrows(LearnException.class, () -> s.confirm("adai", true));

        assertTrue(e.getMessage().contains("已经过期"), e.getMessage());
        ArgumentCaptor<LearnDigestTask> captor = ArgumentCaptor.forClass(LearnDigestTask.class);
        verify(taskRepository).save(eq("adai"), captor.capture());
        assertEquals("expired", captor.getValue().status());
    }

    /** 反过来：没有过期账时保持老话术（不把「没有等我确认的」说成「已经过期」）。 */
    @Test
    void confirm_whenNothingAwaiting_keepsOriginalHumanMessage() {
        LearnDigestAppService s = serviceWithTasks(capturingExecutor);
        when(taskRepository.findRecent(eq("adai"), anyInt())).thenReturn(List.of());

        LearnException e = assertThrows(LearnException.class, () -> s.confirm("adai", true));

        assertEquals("现在没有等待确认的整理任务", e.getMessage());
    }

    /**
     * TTL 到期（内存入口过期）→ 状态回 idle，**账上那条一并 expired**——两套生命周期对齐。
     * 时间不可注入，用反射把内存 job 的 settledAt 拨到 31 分钟前（TTL = 30 分钟）。
     */
    @Test
    void digestJobStatus_confirmTtlExpired_marksAccountExpired() throws Exception {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(
                new com.adaiadai.core.domain.learn.LearnSource(
                        "bilibili", "BV1xx411c7mD", "https://www.bilibili.com/video/BV1xx411c7mD",
                        "某视频", "某UP", "2026-05-05", null, true, "https://audio/x.m4s", 2244, List.of()));
        when(transcriptionService.unavailableReason()).thenReturn(null);
        when(transcriptionService.estimate(eq("adai"), any())).thenReturn(
                new LearnTranscriptionService.CostEstimate(2244, true, 0.18d, 0, 36000, 36000, false));
        LearnDigestAppService s = serviceWithTasks(directExecutor);

        s.submit("adai", new LearnDigestAppService.DigestRequest(
                "https://www.bilibili.com/video/BV1xx411c7mD", null, null, null, null, null));
        assertEquals(LearnDigestAppService.STATUS_NEEDS_CONFIRMATION, s.digestJobStatus("adai").status());
        assertEquals("needs_confirmation", lastRecordedTask().status(), "先入账「等你拍板」");

        ageJobSettledAt(s, "adai", 31 * 60_000L);

        assertEquals(LearnDigestAppService.STATUS_IDLE, s.digestJobStatus("adai").status());
        LearnDigestTask expired = lastRecordedTask();
        assertEquals("expired", expired.status());
        assertEquals(LearnDigestAppService.EXPIRED_CONFIRM_MESSAGE, expired.message());
    }

    /** 把内存 job 的 settledAt 拨回过去（TTL 判定用墙钟，测试无法等待 30 分钟）。 */
    @SuppressWarnings("unchecked")
    private static void ageJobSettledAt(LearnDigestAppService s, String userId, long millis) throws Exception {
        java.lang.reflect.Field jobsField = LearnDigestAppService.class.getDeclaredField("jobs");
        jobsField.setAccessible(true);
        Map<String, Object> jobs = (Map<String, Object>) jobsField.get(s);
        Object job = jobs.get(userId);
        assertNotNull(job, "内存里应有一个 job");
        java.lang.reflect.Field settled = job.getClass().getDeclaredField("settledAt");
        settled.setAccessible(true);
        settled.setLong(job, System.currentTimeMillis() - millis);
    }

    /** 造一个卡在「等你拍板」的付费任务（无字幕视频路径）；返回已提交的服务实例。 */
    private LearnDigestAppService awaitingConfirmService(Executor executor) {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(
                new com.adaiadai.core.domain.learn.LearnSource(
                        "bilibili", "BV1xx411c7mD", "https://www.bilibili.com/video/BV1xx411c7mD",
                        "某视频", "某UP", "2026-05-05", null, true, "https://audio/x.m4s", 2244, List.of()));
        when(transcriptionService.unavailableReason()).thenReturn(null);
        when(transcriptionService.estimate(eq("adai"), any())).thenReturn(
                new LearnTranscriptionService.CostEstimate(2244, true, 0.18d, 0, 36000, 36000, false));
        LearnDigestAppService s = serviceWithTasks(executor);
        s.submit("adai", new LearnDigestAppService.DigestRequest(
                "https://www.bilibili.com/video/BV1xx411c7mD", null, null, null, null, null));
        assertEquals(LearnDigestAppService.STATUS_NEEDS_CONFIRMATION, s.digestJobStatus("adai").status());
        return s;
    }

    /** TTL 内确认照常：付费路径不该把没过期的入口误判成过期。 */
    @Test
    void confirm_withinTtl_proceedsToTranscribe() {
        when(transcriptionService.transcribe(eq("adai"), any())).thenReturn(
                new LearnTranscriptionService.TranscriptionResult("转写稿正文", false, 2244, 0.18d, null));
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);
        LearnDigestAppService s = awaitingConfirmService(directExecutor);

        LearnDigestAppService.DigestJobStatus after = s.confirm("adai", true);

        verify(transcriptionService, times(1)).transcribe(eq("adai"), any());
        assertEquals(LearnDigestAppService.STATUS_DONE, after.status(), "转写完照常落卡");
    }

    /**
     * 对抗审查 C（2026-10-05 点名「没有任何批内测试钉住」的那条）：**客户端从不轮询**时，
     * 超 30 分钟再点头也不能花钱——付费路径自己校验 TTL，抛过期人话、转写零调用、账上标 expired，
     * 与读路径（轮询过）得到**同一个结果**。
     *
     * <p>反向验证：把 TTL 校验短路（{@code isConfirmExpired} 恒 false）→ 本用例红（不抛、照转写）。
     */
    @Test
    void confirm_afterTtl_throwsExpired_andNeverTranscribes() throws Exception {
        LearnDigestAppService s = awaitingConfirmService(directExecutor);
        ageJobSettledAt(s, "adai", 31 * 60_000L);   // 31 分钟 > 30 分钟 TTL

        LearnException e = assertThrows(LearnException.class, () -> s.confirm("adai", true));

        assertEquals(LearnDigestAppService.EXPIRED_CONFIRM_MESSAGE, e.getMessage());
        verify(transcriptionService, never()).transcribe(anyString(), any());
        assertEquals("expired", lastRecordedTask().status(), "账上如实标过期");
        assertEquals(LearnDigestAppService.EXPIRED_CONFIRM_MESSAGE, lastRecordedTask().message());
    }

    /**
     * 对抗审查 D 守卫（2026-10-05）：**TTL 内、入口还在**的待确认任务，读账路径不得把它标 expired。
     *
     * <p>守卫 {@code if (!isConfirmExpired(alive)) continue;} 此前短路成 {@code if(false)} 时
     * LearnDigestAppServiceTest + LearnDigestEndpointTest 104/104 依然全绿——这条缺口用本用例钉住。
     *
     * <p>反向验证：把该守卫短路成 {@code if (false)} → 本用例红（TTL 内被标成 expired）。
     */
    @Test
    void tasks_confirmWithinTtl_doesNotMarkExpired() throws Exception {
        LearnDigestAppService s = awaitingConfirmService(directExecutor);
        LearnDigestTask pending = lastRecordedTask();
        assertEquals("needs_confirmation", pending.status(), "先入账「等你拍板」");
        when(taskRepository.findRecent(eq("adai"), anyInt())).thenReturn(List.of(pending));
        ageJobSettledAt(s, "adai", 5 * 60_000L);   // 5 分钟，远在 30 分钟 TTL 内
        org.mockito.Mockito.clearInvocations(taskRepository);

        List<LearnDigestTask> listed = s.tasks("adai", 20);

        assertEquals(1, listed.size());
        verify(taskRepository, never()).save(anyString(), any(LearnDigestTask.class));
        // 入口也还在：状态照旧「等你拍板」，账与内存不打架
        assertEquals(LearnDigestAppService.STATUS_NEEDS_CONFIRMATION, s.digestJobStatus("adai").status());
    }

    /**
     * P2-learn34 的核心契约（2026-10-05 独立审查实测补钉）：**账标 expired ⇔ 内存决策入口同时消失**。
     *
     * <p>两处必须同一时刻成立：只清内存不写账 → 学习页永久留一条点不动的「待确认」；
     * **只写账不清内存 → 用户还能靠旧的内存 job 走 confirm 花掉一笔钱**（状态自相矛盾）。
     * 变异「只标账、内存 job 原样留着」在加本用例前 103/103 全绿。
     *
     * <p>注意顺序：**先断言账、紧接着 confirm**——不能先调 {@code digestJobStatus}（它自己也会清理
     * 内存入口，会把变异体的破绽顺手抹掉，用例就白写了）。
     */
    @Test
    void expireStaleConfirmTasks_marksAccountExpired_andClosesThePaidEntry() throws Exception {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(
                new com.adaiadai.core.domain.learn.LearnSource(
                        "bilibili", "BV1xx411c7mD", "https://www.bilibili.com/video/BV1xx411c7mD",
                        "某视频", "某UP", "2026-05-05", null, true, "https://audio/x.m4s", 2244, List.of()));
        when(transcriptionService.unavailableReason()).thenReturn(null);
        when(transcriptionService.estimate(eq("adai"), any())).thenReturn(
                new LearnTranscriptionService.CostEstimate(2244, true, 0.18d, 0, 36000, 36000, false));
        LearnDigestAppService s = serviceWithTasks(directExecutor);

        s.submit("adai", new LearnDigestAppService.DigestRequest(
                "https://www.bilibili.com/video/BV1xx411c7mD", null, null, null, null, null));
        assertEquals(LearnDigestAppService.STATUS_NEEDS_CONFIRMATION, s.digestJobStatus("adai").status());
        LearnDigestTask pending = lastRecordedTask();
        assertEquals("needs_confirmation", pending.status(), "先入账「等你拍板」");
        // 账上那条就是内存 job 的那条（真实仓储按 id 对上号；测试替身要如实回放）
        when(taskRepository.findRecent(eq("adai"), anyInt())).thenReturn(List.of(pending));

        // TTL 到期：走「看清单」这条扫描路径（expireStaleConfirmTasks），不是 digestJobStatus
        ageJobSettledAt(s, "adai", 31 * 60_000L);
        s.tasks("adai", 20);

        // ① 账上如实标过期
        LearnDigestTask expired = lastRecordedTask();
        assertEquals("expired", expired.status(), "① 账上必须标过期");
        assertEquals(LearnDigestAppService.EXPIRED_CONFIRM_MESSAGE, expired.message(),
                "过期要给人话，不能留个空状态");
        assertFalse(expired.inProgress(), "不能再显示成「等用户拍板」");

        // ② 内存决策入口必须同时消失：紧接着点头不能花钱
        LearnException e = assertThrows(LearnException.class, () -> s.confirm("adai", true),
                "入口已消失时 confirm 必须拒绝，不得再走一遍转写");
        assertNotNull(e.getMessage());
        verify(transcriptionService, never()).transcribe(anyString(), any());
    }

    // ── ③ P2-learn33（2026-10-05）：「展开」——索引卡 → 衍生全文卡 ──

    private static final String EXPAND_JSON = """
            {"title":"无人机外卖航线的成本账","summary":"低空物流先用高频小单把航线跑通",
             "key_points":["航线固定才有成本优势","单量决定能不能活","政策先行是启动条件"],
             "full_text":"## 一、为什么是杭州\\n\\n先把航线跑通，再谈规模。"}""";

    /** 一张停在索引层的卡（有主题、有素材可查）。 */
    private static LearnCard expandableCard() {
        return new LearnCard("other", "杭州无人机外卖航线", "bilibili", "某UP",
                "https://www.bilibili.com/video/BV1xx411c7mD", "2026-05-05",
                LocalDate.of(2026, 9, 25), "new", false, null,
                List.of("低空经济"), "航线已开通", List.of("三条航线"), List.of(), "",
                "低空经济");
    }

    /** 展开：落衍生卡（三行要点 + 全文），**原卡不覆盖**，关系记在 derivedFrom。 */
    @Test
    void expandCard_generatesDerivedCardWithThreePoints_withoutTouchingOriginal() {
        when(repository.find("adai", "other", "杭州无人机外卖航线")).thenReturn(Optional.of(expandableCard()));
        when(repository.expandedTitle("adai", "other", "杭州无人机外卖航线")).thenReturn(null);
        // P2-learn33 审查 A：素材按**这张卡自己记下的来源**取（不再是主题目录 rawAssets）
        when(repository.sourceAssets("adai", "other", "杭州无人机外卖航线"))
                .thenReturn(List.of("transcript.txt"));
        when(repository.readRaw("adai", "transcript.txt")).thenReturn("原始转写稿：杭州开通三条无人机外卖航线……");
        when(repository.existsOn(eq("adai"), eq("other"), any(), anyString())).thenReturn(false);
        when(aiClient.generate(any(), any())).thenReturn(EXPAND_JSON);
        LearnDigestAppService s = serviceWithTasks(directExecutor);

        LearnDigestAppService.ExpansionResult r = s.expandCard("adai", "other", "杭州无人机外卖航线");

        assertEquals("expanded", r.status());
        assertEquals("杭州无人机外卖航线", r.derivedFrom(), "衍生关系指向原卡（不就地覆盖）");
        ArgumentCaptor<LearnCard> captor = ArgumentCaptor.forClass(LearnCard.class);
        verify(repository).saveDerived(eq("adai"), captor.capture(), eq("杭州无人机外卖航线"), anyString());
        LearnCard derived = captor.getValue();
        assertEquals("无人机外卖航线的成本账", derived.title());
        assertEquals(3, derived.keyPoints().size(), "三行要点");
        assertEquals("低空物流先用高频小单把航线跑通", derived.coreView());
        assertEquals("低空经济", derived.topic(), "衍生卡留在原主题里");
        assertTrue(derived.tags().contains("展开"), "衍生卡带「展开」标签，列表里认得出");
        verify(repository, never()).save(eq("adai"), any(LearnCard.class), anyList());
    }

    /** 已展开过 → 幂等返回那张衍生卡，不再烧模型。 */
    @Test
    void expandCard_alreadyExpanded_isIdempotentWithoutBurningModel() {
        when(repository.find("adai", "other", "杭州无人机外卖航线")).thenReturn(Optional.of(expandableCard()));
        when(repository.expandedTitle("adai", "other", "杭州无人机外卖航线")).thenReturn("无人机外卖航线的成本账");
        LearnDigestAppService s = serviceWithTasks(directExecutor);

        LearnDigestAppService.ExpansionResult r = s.expandCard("adai", "other", "杭州无人机外卖航线");

        assertEquals("exists", r.status());
        assertEquals("无人机外卖航线的成本账", r.title());
        verifyNoInteractions(aiClient);
        verify(repository, never()).saveDerived(anyString(), any(LearnCard.class), anyString(), anyString());
    }

    /** 没留素材 → 人话拒绝（不硬编一篇），且不烧模型。 */
    @Test
    void expandCard_withoutMaterial_throwsHumanMessageWithoutBurningModel() {
        when(repository.find("adai", "other", "杭州无人机外卖航线")).thenReturn(Optional.of(expandableCard()));
        when(repository.expandedTitle("adai", "other", "杭州无人机外卖航线")).thenReturn(null);
        when(repository.sourceAssets("adai", "other", "杭州无人机外卖航线")).thenReturn(List.of());
        LearnDigestAppService s = serviceWithTasks(directExecutor);

        LearnException e = assertThrows(LearnException.class,
                () -> s.expandCard("adai", "other", "杭州无人机外卖航线"));

        assertTrue(e.getMessage().contains("没留下原始素材"), e.getMessage());
        verifyNoInteractions(aiClient);
    }

    /** 待展开清单：有素材、无衍生卡 → hasSource=true / expanded=false（前端据此亮「待展开」）。 */
    @Test
    void expansions_reportsPendingAndExpandedState() {
        when(repository.list("adai", "other")).thenReturn(List.of(expandableCard()));
        when(repository.list("adai", "ai")).thenReturn(List.of());
        when(repository.list("adai", "trading")).thenReturn(List.of());
        when(repository.sourceAssets("adai", "other", "杭州无人机外卖航线"))
                .thenReturn(List.of("transcript.txt"));
        when(repository.readRaw("adai", "transcript.txt")).thenReturn("原始转写稿：杭州开通三条无人机外卖航线……");
        when(repository.expandedTitle("adai", "other", "杭州无人机外卖航线")).thenReturn(null);
        LearnDigestAppService s = serviceWithTasks(directExecutor);

        List<LearnDigestAppService.ExpansionState> states = s.expansions("adai");

        assertEquals(1, states.size());
        LearnDigestAppService.ExpansionState st = states.get(0);
        assertEquals("other", st.type());
        assertEquals("杭州无人机外卖航线", st.title());
        assertTrue(st.hasSource(), "有素材 → 可展开");
        assertFalse(st.expanded(), "还没有衍生卡 → 待展开");
        assertNull(st.expandedTitle());
    }

    /**
     * 对抗审查 A 复现（2026-10-05）：**同主题**里 A 卡有素材、B 卡没有 →
     * 展开 B 绝不能借用 A 的素材，也不得调用模型；B 的「待展开」徽标必须是 false。
     *
     * <p>旧实现按**主题目录**（{@code repository.rawAssets(type, topic)}）取素材，而素材名
     * （{@code pasted-<hash>.txt} / 转写稿）**不含卡名**、{@code _raw/} 又是主题级目录——
     * 于是 B 的展开 prompt 里会塞进 A 的转写稿，用户点一下就得到一张内容对不上的衍生卡。
     *
     * <p>反向验证：把 {@code cardMaterials} 的取值改回 {@code rawAssets(type, topic)} → 本用例红。
     */
    @Test
    void expandCard_cardWithoutOwnAssets_neverBorrowsSameTopicSiblingAssets() {
        LearnCard sibling = new LearnCard("other", "同主题的另一张卡", "bilibili", "某UP",
                "https://www.bilibili.com/video/BV1yy411c7mE", "2026-05-06",
                LocalDate.of(2026, 9, 26), LearnCard.STATUS_NEW, false, null,
                List.of("低空经济"), "另一张卡的观点", List.of("另一张的要点"), List.of(), "",
                "低空经济");
        when(repository.find("adai", "other", "同主题的另一张卡")).thenReturn(Optional.of(sibling));
        when(repository.expandedTitle("adai", "other", "同主题的另一张卡")).thenReturn(null);
        // 同主题目录里确实躺着 A 卡的素材（旧实现就是靠这一步把 A 的稿子喂给 B）
        when(repository.rawAssets("adai", "other", "低空经济")).thenReturn(List.of("transcript.txt"));
        when(repository.readRaw("adai", "transcript.txt")).thenReturn("A 卡的转写稿，跟这张卡不是一回事");
        // B 卡自己没记下任何素材
        when(repository.sourceAssets("adai", "other", "同主题的另一张卡")).thenReturn(List.of());
        LearnDigestAppService s = serviceWithTasks(directExecutor);

        LearnException e = assertThrows(LearnException.class,
                () -> s.expandCard("adai", "other", "同主题的另一张卡"));

        assertTrue(e.getMessage().contains("没留下原始素材"), e.getMessage());
        verifyNoInteractions(aiClient);
        verify(repository, never()).saveDerived(anyString(), any(LearnCard.class), anyString(), anyString());

        // 双端「待展开」徽标与展开判据同源：B 卡 hasSource=false（否则用户点一下才发现没素材）
        when(repository.list("adai", "other")).thenReturn(List.of(sibling));
        when(repository.list("adai", "ai")).thenReturn(List.of());
        when(repository.list("adai", "trading")).thenReturn(List.of());
        List<LearnDigestAppService.ExpansionState> states = s.expansions("adai");
        assertEquals(1, states.size());
        assertFalse(states.get(0).hasSource(), "B 卡自己没素材 → 徽标不得亮");
    }

    /**
     * 对抗审查 A 的另一半：消化落卡时就要把「这张卡用了哪些素材」记进卡自己的 frontmatter
     * （{@code source_assets}）——素材名不含卡名，不记就没法按卡取用。
     */
    @Test
    void submit_recordsSourceAssetsOnTheCardItCameFrom() {
        when(aiClient.generate(any(), any())).thenReturn(TOPIC_JSON);
        String pastedName = "pasted-" + LearnDigestAppService.shortHash("这是一段粘进来的正文……") + ".txt";
        when(repository.promoteRaw(eq("adai"), eq(LearnCard.TYPE_TRADING), eq("量价关系"), anyList()))
                .thenReturn(List.of(pastedName));
        LearnDigestAppService direct = new LearnDigestAppService(
                aiClient, repository, directExecutor, fetchService, transcriptionService);

        direct.submit("adai", "这是一段粘进来的正文……", "trading", "web", "某人", null, null);

        verify(repository).writeSourceAssets(eq("adai"), eq(LearnCard.TYPE_TRADING),
                eq("回调一半的判定"), eq(List.of(pastedName)));
    }

    /**
     * P2-learn33 审查 A 的**端到端闭环**（真实文件仓储 + 内存盘）：消化落卡时把素材名记进
     * frontmatter → 展开时按这份记录读回同一份素材、喂进 prompt。
     *
     * <p>钉住「写进去的名字」与「展开时去读的名字」一致（归位遇同名不同内容会改名
     * {@code versionedName}，两边一旦分家，卡就永远展开不了或读错文件）。
     */
    @Test
    void expandCard_realStorage_readsBackTheAssetsRecordedOnTheCard() {
        var storage = new com.adaiadai.core.infrastructure.storage.InMemoryFileStorage();
        var realRepo = new com.adaiadai.core.infrastructure.storage.LearnCardFileRepository(storage);
        when(aiClient.generate(any(), any())).thenReturn(TOPIC_JSON, EXPAND_JSON);
        LearnDigestAppService direct = new LearnDigestAppService(
                aiClient, realRepo, directExecutor, fetchService, transcriptionService);

        direct.submit("adai", "这是一段粘进来的正文……", "trading", "web", "某人", null, null);

        String materialName = "pasted-" + LearnDigestAppService.shortHash("这是一段粘进来的正文……") + ".txt";
        assertEquals(List.of(materialName),
                realRepo.sourceAssets("adai", LearnCard.TYPE_TRADING, "回调一半的判定"),
                "落卡时把「这张卡用了哪份素材」记进 frontmatter");
        assertEquals("这是一段粘进来的正文……", realRepo.readRaw("adai", materialName),
                "素材确实归位到主题目录，按记录读得回来");

        LearnDigestAppService.ExpansionResult r =
                direct.expandCard("adai", LearnCard.TYPE_TRADING, "回调一半的判定");

        assertEquals("expanded", r.status());
        ArgumentCaptor<com.adaiadai.core.kernel.context.engine.ContextPackage> captor =
                ArgumentCaptor.forClass(com.adaiadai.core.kernel.context.engine.ContextPackage.class);
        verify(aiClient, times(2)).generate(captor.capture(), any());
        String expandPrompt = captor.getAllValues().get(1).prompt();
        assertTrue(expandPrompt.contains(materialName), "展开 prompt 里是这张卡自己记录的素材：" + expandPrompt);
        assertTrue(expandPrompt.contains("这是一段粘进来的正文……"), "喂的是素材正文本身");
    }

    /**
     * 对抗审查 B 复现（2026-10-05）：两端**同时**展开同一张卡 → 模型只被调 1 次、只落 1 张衍生卡，
     * 第二次拿到如实的「正在展开」；旧实现是 check-then-act，两边各烧一次模型、可能落两张卡。
     *
     * <p>时序刻意做成「第一次的模型调用被卡住，第二次必须在这个窗口里进来」——否则去掉占位后
     * 第二次会走「已展开」幂等分支，用例红不了（变异验证不到）。
     *
     * <p>反向验证：去掉 {@code expandInFlight} 占位 → 本用例红（模型调用 2 次、落 2 张卡）。
     */
    @Test
    void expandCard_concurrentSameCard_burnsModelOnce() throws Exception {
        when(repository.find("adai", "other", "杭州无人机外卖航线")).thenReturn(Optional.of(expandableCard()));
        when(repository.expandedTitle("adai", "other", "杭州无人机外卖航线")).thenReturn(null);
        when(repository.sourceAssets("adai", "other", "杭州无人机外卖航线"))
                .thenReturn(List.of("transcript.txt"));
        when(repository.readRaw("adai", "transcript.txt")).thenReturn("原始转写稿：杭州开通三条无人机外卖航线……");
        when(repository.existsOn(eq("adai"), eq("other"), any(), anyString())).thenReturn(false);

        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        when(aiClient.generate(any(), any())).thenAnswer(inv -> {
            if (modelCalls.incrementAndGet() == 1) {
                modelEntered.countDown();
                assertTrue(releaseModel.await(10, TimeUnit.SECONDS), "等测试放行第一次模型调用");
            }
            return EXPAND_JSON;
        });
        LearnDigestAppService s = serviceWithTasks(directExecutor);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<LearnDigestAppService.ExpansionResult> first = pool.submit(
                    () -> s.expandCard("adai", "other", "杭州无人机外卖航线"));
            assertTrue(modelEntered.await(10, TimeUnit.SECONDS), "第一次展开应已进入模型调用");
            Future<LearnDigestAppService.ExpansionResult> second = pool.submit(
                    () -> s.expandCard("adai", "other", "杭州无人机外卖航线"));
            LearnDigestAppService.ExpansionResult secondResult = second.get(10, TimeUnit.SECONDS);
            releaseModel.countDown();
            LearnDigestAppService.ExpansionResult firstResult = first.get(10, TimeUnit.SECONDS);

            assertEquals("expanded", firstResult.status());
            assertEquals(1, modelCalls.get(), "并发两次展开只允许烧 1 次模型");
            verify(repository, times(1)).saveDerived(anyString(), any(LearnCard.class), anyString(), anyString());
            assertTrue("exists".equals(secondResult.status()) || "expanding".equals(secondResult.status()),
                    "并发第二次必须如实回「正在展开/已展开」，实际=" + secondResult.status());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void submit_llmFailure_jobFailed_savesRawSourceNotHalfCard() {
        LearnDigestAppService direct = new LearnDigestAppService(aiClient, repository, directExecutor, fetchService, transcriptionService);
        when(aiClient.generate(any(), any())).thenThrow(new RuntimeException("llm down"));

        LearnDigestAppService.DigestSubmitResult result =
                direct.submit("adai", "字幕内容", null, null, null, null, null);
        assertEquals(LearnDigestAppService.STATUS_PENDING, result.status());

        LearnDigestAppService.DigestJobStatus job = direct.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_FAILED, job.status());
        assertTrue(job.message().contains("素材已留存"));
        verify(repository).saveRawSource(eq("adai"), anyString());
        verify(repository, never()).save(anyString(), any(LearnCard.class), anyList());
    }

    @Test
    void submit_rejectedByExecutor_throws400HumanMessage() {
        Executor rejecting = r -> {
            throw new RejectedExecutionException("full");
        };
        LearnDigestAppService svc = new LearnDigestAppService(aiClient, repository, rejecting, fetchService, transcriptionService);

        LearnException e = assertThrows(LearnException.class,
                () -> svc.submit("adai", "字幕内容", null, null, null, null, null));
        assertTrue(e.getMessage().contains("繁忙"));
        assertEquals(LearnDigestAppService.STATUS_IDLE, svc.digestJobStatus("adai").status());
        verify(repository, never()).save(anyString(), any(LearnCard.class), anyList());
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
        LearnDigestAppService direct = new LearnDigestAppService(aiClient, repository, directExecutor, fetchService, transcriptionService);
        direct.submit("adai", "字幕内容", null, null, null, null, null);
        assertEquals(LearnDigestAppService.STATUS_DONE, direct.digestJobStatus("adai").status());
        assertEquals(LearnCard.DEFAULT_TOPIC, direct.digestJobStatus("adai").topic(),
                "done 回传 topic（前端不必再打一次 /learn/content 才知道归到哪）");
        // TTL 边界不可注入时钟，此处仅验证同一 job 重复查询幂等（done 保留至消费/覆盖）
        assertEquals(LearnDigestAppService.STATUS_DONE, direct.digestJobStatus("adai").status());
    }

    // ── 结构统一批（2026-09-12）：主题目录契约 + 素材归位 ──

    private static final String TOPIC_JSON = """
            {"title":"回调一半的判定","type":"trading","topic":"量价关系","tags":["止损"],
            "core_view":"回调到一半才是买点","key_points":["02:31 几何口径"],
            "questions":[],"trade_related":true,"trade_note":""}""";

    @Test
    void digest_llmTopic_landsOnCard() {
        when(aiClient.generate(any(), any())).thenReturn(TOPIC_JSON);

        LearnCard card = service.digest("adai", "字幕……", null, "bilibili", "UP", null, null);

        assertEquals("量价关系", card.topic(), "LLM 给的主题落到卡片上（决定落盘目录）");
    }

    @Test
    void digest_llmTopicMissing_fallsBackToDefaultTopic() {
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON); // 无 topic 字段

        LearnCard card = service.digest("adai", "字幕……", null, "bilibili", "UP", null, null);

        assertEquals(LearnCard.DEFAULT_TOPIC, card.topic(), "没给主题 → 缺省主题，而不是空目录名");
    }

    @Test
    void digest_promptListsExistingTopics_soSameTopicMerges() {
        when(repository.topics("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of("量价关系"));
        when(aiClient.generate(any(), any())).thenReturn(TOPIC_JSON);

        service.digest("adai", "字幕……", "trading", "bilibili", "UP", null, null);

        verify(aiClient).generate(argThat(ctx -> ctx.prompt().contains("量价关系")
                && ctx.prompt().contains("完全同名")), any());
    }

    @Test
    void submit_pastedMaterial_archivesRawAndPromotesIntoTopic() {
        when(aiClient.generate(any(), any())).thenReturn(TOPIC_JSON);
        LearnDigestAppService direct = new LearnDigestAppService(
                aiClient, repository, directExecutor, fetchService, transcriptionService);

        direct.submit("adai", "这是一段粘进来的正文……", "trading", "web", "某人", null, null);

        String expectedName = "pasted-" + LearnDigestAppService.shortHash("这是一段粘进来的正文……") + ".txt";
        verify(repository).saveRaw(eq("adai"), eq(expectedName), eq("这是一段粘进来的正文……"));
        verify(repository).promoteRaw(eq("adai"), eq(LearnCard.TYPE_TRADING), eq("量价关系"),
                argThat(names -> names.contains(expectedName)));
    }

    // ── 图片源（2026-09-12 完整升级批：书页/PPT/截图）──

    private final com.adaiadai.core.infrastructure.ai.vision.VisualAiClient visualAiClient =
            mock(com.adaiadai.core.infrastructure.ai.vision.VisualAiClient.class);

    private LearnDigestAppService withVision() {
        return new LearnDigestAppService(aiClient, repository, directExecutor, fetchService,
                transcriptionService, visualAiClient);
    }

    @Test
    void submitImages_readsImageThenDigests_andPromotesOriginalImage() {
        when(visualAiClient.ask(any(), any(), any())).thenReturn("第一页：量价关系的三个层次……");
        when(aiClient.generate(any(), any())).thenReturn(TOPIC_JSON);

        withVision().submitImages("adai",
                List.of(new LearnDigestAppService.ImageInput(new byte[]{1, 2, 3}, "image/png", "p1.png")),
                "trading", "书页第 3 页");

        verify(repository).saveRawBytes(eq("adai"), argThat(n -> n.startsWith("image-1-") && n.endsWith(".png")),
                argThat(b -> java.util.Arrays.equals(b, new byte[]{1, 2, 3})));
        verify(visualAiClient).ask(argThat(req -> "image/png".equals(req.contentType())
                && "书页第 3 页".equals(req.caption())), anyString(), any());
        verify(repository).save(eq("adai"), argThat(c -> LearnCard.TYPE_TRADING.equals(c.type())
                && "量价关系".equals(c.topic())), anyList());
        verify(repository).promoteRaw(eq("adai"), eq(LearnCard.TYPE_TRADING), eq("量价关系"),
                argThat(names -> names.stream().anyMatch(n -> n.startsWith("image-1-"))));
    }

    @Test
    void submitImages_note_isPassedIntoTheQuestion_notOnlyCaption() {
        // 对抗审查指出：ImageRequest.caption 会被视觉客户端的 ask 丢弃 → note 实际无效。
        // 修复后 note 并进问题文本，模型才真的看得到用户的补充说明。
        when(visualAiClient.ask(any(), anyString(), any())).thenReturn("第一页：可转债双低……");
        when(aiClient.generate(any(), any())).thenReturn(TOPIC_JSON);

        withVision().submitImages("adai",
                List.of(new LearnDigestAppService.ImageInput(new byte[]{1, 2, 3}, "image/png", "p1.png")),
                null, "这是可转债策略讲义第 3 页");

        verify(visualAiClient).ask(any(), argThat(q -> q.contains("用户补充说明")
                && q.contains("可转债策略讲义第 3 页") && q.contains("逐字抄录")), any());
    }

    @Test
    void submitImages_emptyOcrResult_failsVisibleWithoutCard() {
        when(visualAiClient.ask(any(), any(), any())).thenReturn("   ");

        LearnDigestAppService svc = withVision();
        svc.submitImages("adai",
                List.of(new LearnDigestAppService.ImageInput(new byte[]{9}, "image/jpeg", "p.jpg")), null, null);

        assertEquals(LearnDigestAppService.STATUS_FAILED, svc.digestJobStatus("adai").status());
        assertTrue(svc.digestJobStatus("adai").message().contains("没读出内容"));
        verify(repository, never()).save(any(), any(), anyList());
    }

    @Test
    void submitImages_tooMany_throwsBeforeSavingAnything() {
        List<LearnDigestAppService.ImageInput> four = List.of(
                new LearnDigestAppService.ImageInput(new byte[]{1}, "image/png", null),
                new LearnDigestAppService.ImageInput(new byte[]{2}, "image/png", null),
                new LearnDigestAppService.ImageInput(new byte[]{3}, "image/png", null),
                new LearnDigestAppService.ImageInput(new byte[]{4}, "image/png", null));

        LearnException e = assertThrows(LearnException.class,
                () -> withVision().submitImages("adai", four, null, null));

        assertTrue(e.getMessage().contains("最多"), e.getMessage());
        verify(repository, never()).saveRawBytes(any(), any(), any());
    }

    @Test
    void submitImages_withoutVisualModel_throwsHumanMessage() {
        LearnException e = assertThrows(LearnException.class, () -> service.submitImages("adai",
                List.of(new LearnDigestAppService.ImageInput(new byte[]{1}, "image/png", null)), null, null));

        assertTrue(e.getMessage().contains("视觉模型"), "没接视觉模型要说人话，而不是空指针：" + e.getMessage());
    }

    // ── 找卡片 / 读全文（2026-09-12 完整升级批：「打开那篇」+ 学习页搜索）──

    @Test
    void find_titleHitBeatsTopicHit_andRespectsLimit() {
        LearnCard titleHit = new LearnCard(LearnCard.TYPE_AI, "量价关系入门", "web", null, null, null,
                LocalDate.of(2026, 9, 1), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of(), List.of(), "", "其他主题");
        LearnCard topicHit = new LearnCard(LearnCard.TYPE_AI, "另一篇", "web", null, null, null,
                LocalDate.of(2026, 9, 2), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of(), List.of(), "", "量价关系");
        when(repository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(topicHit, titleHit));

        List<LearnCard> found = service.find("adai", "量价关系", null);

        assertEquals(2, found.size());
        assertEquals("量价关系入门", found.get(0).title(), "标题命中权重高于主题命中");
    }

    @Test
    void find_blankQuery_returnsEmptyWithoutTouchingRepository() {
        assertTrue(service.find("adai", "   ", null).isEmpty());
        verify(repository, never()).list(any(), any());
    }

    @Test
    void content_returnsRawMarkdown_missingThrowsHuman() {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "全文卡", "web", null, null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of(), List.of(), "", "量价关系");
        when(repository.find("adai", LearnCard.TYPE_AI, "全文卡")).thenReturn(java.util.Optional.of(card));
        when(repository.readCard("adai", LearnCard.TYPE_AI, "全文卡")).thenReturn("# 全文卡\n\n## 关键内容详解\n正文");

        LearnDigestAppService.CardContent content = service.content("adai", LearnCard.TYPE_AI, "全文卡");

        assertEquals("量价关系", content.topic());
        assertTrue(content.content().contains("关键内容详解"), "按 md 原文返回（手工卡的段不丢）");

        LearnException e = assertThrows(LearnException.class,
                () -> service.content("adai", LearnCard.TYPE_AI, "没这张"));
        assertTrue(e.getMessage().contains("卡片不存在"));
    }

    // ── 对抗审查修复批（2026-09-12）：P1-B 占位回收、P2-7 body ──

    @Test
    void submitImages_stagingFailure_reclaimsSlot_andReportsHumanMessage() {
        // P1-B：原图写暂存失败（磁盘满/权限）→ 异常直穿 500 且 job 留非终态 →
        // 该用户之后所有喂入被「有任务在跑」永久挡死。修复后：回收占位 + 人话 400。
        org.mockito.Mockito.doThrow(new com.adaiadai.core.infrastructure.storage.StorageException("磁盘满"))
                .when(repository).saveRawBytes(anyString(), anyString(), any());
        LearnDigestAppService svc = withVision();

        LearnException e = assertThrows(LearnException.class, () -> svc.submitImages("adai",
                List.of(new LearnDigestAppService.ImageInput(new byte[]{1, 2}, "image/png", "p.png")), null, null));

        assertTrue(e.getMessage().contains("稍后再试"), "要说人话而不是 500：" + e.getMessage());
        assertEquals(LearnDigestAppService.STATUS_IDLE, svc.digestJobStatus("adai").status(),
                "占位已回收（否则后续喂入永远 running）");
        // 关键回归：下一个喂入还能正常受理
        when(aiClient.generate(any(), any())).thenReturn(TRADING_JSON);
        LearnDigestAppService direct = new LearnDigestAppService(aiClient, repository, directExecutor,
                fetchService, transcriptionService, visualAiClient);
        direct.submit("adai", "素材正文", null, null, null, null, null);
        assertEquals(LearnDigestAppService.STATUS_DONE, direct.digestJobStatus("adai").status(),
                "换成同一仓储后新喂入照常完成（证明前一次失败没钉死入口）");
    }

    @Test
    void submitImages_executorRejected_cleansStagedOriginals() {
        java.util.concurrent.Executor rejecting = command -> {
            throw new RejectedExecutionException("queue full");
        };
        LearnDigestAppService svc = new LearnDigestAppService(aiClient, repository, rejecting,
                fetchService, transcriptionService, visualAiClient);
        when(repository.readRawBytes(anyString(), anyString())).thenReturn(new byte[]{1});
        List<LearnDigestAppService.ImageInput> images = List.of(
                new LearnDigestAppService.ImageInput(new byte[]{1}, "image/png", "p1.png"),
                new LearnDigestAppService.ImageInput(new byte[]{2}, "image/jpeg", "p2.jpg"));

        LearnException e = assertThrows(LearnException.class, () -> svc.submitImages("adai", images, null, null));

        assertTrue(e.getMessage().contains("繁忙"), e.getMessage());
        verify(repository, times(2)).deleteRaw(eq("adai"), anyString());
        assertEquals(LearnDigestAppService.STATUS_IDLE, svc.digestJobStatus("adai").status());
    }

    @Test
    void content_bodyHasFrontmatterStripped_forDisplay() {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "全文卡", "web", null, null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of(), List.of(), "", "量价关系");
        when(repository.find("adai", LearnCard.TYPE_AI, "全文卡")).thenReturn(java.util.Optional.of(card));
        when(repository.readCard("adai", LearnCard.TYPE_AI, "全文卡"))
                .thenReturn("---\ntitle: 全文卡\norigin: product\n---\n\n## 关键内容详解\n正文");

        LearnDigestAppService.CardContent c = service.content("adai", LearnCard.TYPE_AI, "全文卡");

        assertTrue(c.content().contains("origin: product"), "原文照给（需要完整文件的消费方用）");
        assertFalse(c.body().contains("origin: product"), "展示用的 body 不带内部字段（第一原则）");
        assertTrue(c.body().startsWith("## 关键内容详解"), c.body());
    }

    // ── P1-分享7（2026-09-16）：失败日志的「输入形态」摘要 ──

    @Test
    void urlShape_weiboLink_keepsHostAndPath_butNotQueryContent() {
        String shape = LearnDigestAppService.urlShape(
                "https://weibo.com/1234567890/PdXyZabc?refer_flag=1001030103_");

        assertTrue(shape.contains("weibo.com"), shape);
        assertTrue(shape.contains("/1234567890/PdXyZabc"), shape);
        assertFalse(shape.contains("refer_flag"), "query 内容不得进日志：" + shape);
        assertTrue(shape.contains("query"), shape);
        assertTrue(shape.contains("字节"), shape);
    }

    @Test
    void urlShape_shareText_isReportedAsNonUrlWithCjkCount() {
        String withSpace = LearnDigestAppService.urlShape("分享自@微博 今天大跌，大家还好吗");
        String withoutSpace = LearnDigestAppService.urlShape("分享自微博今天大跌");

        assertTrue(withSpace.startsWith("非完整 URL"), withSpace);
        assertTrue(withSpace.contains("中日韩字符"), withSpace);
        assertTrue(withoutSpace.startsWith("非完整 URL"), withoutSpace);
    }

    @Test
    void urlShape_blankInput_saysBodyPath() {
        assertTrue(LearnDigestAppService.urlShape(null).contains("正文路径"));
        assertTrue(LearnDigestAppService.urlShape("   ").contains("正文路径"));
    }

    // ── P2-learn26（2026-09-16）：图片整理是花钱动作，加一道轻量日配额 ──
    // ── P2-审查5（2026-09-17）：配额「检查 + 记账」必须原子（同一把锁内），否则并发可超卖 ──

    @Test
    void submitImages_dailyQuotaExceeded_refusedWithoutCallingVisionModel() {
        var quota = mock(com.adaiadai.core.domain.learn.LearnQuotaRepository.class);
        when(quota.tryConsumeImages(eq("adai"), any(), anyInt(), anyInt()))
                .thenReturn(new com.adaiadai.core.domain.learn.LearnQuotaRepository
                        .ImageQuotaResult(false, 30));
        LearnDigestAppService svc = new LearnDigestAppService(aiClient, repository, directExecutor,
                fetchService, transcriptionService, visualAiClient, quota, null, 4096, 30);

        LearnException e = assertThrows(LearnException.class, () -> svc.submitImages("adai",
                List.of(new LearnDigestAppService.ImageInput(new byte[]{1, 2, 3}, "image/png", "p1.png")),
                null, null));

        assertTrue(e.getMessage().contains("每天最多 30 张"), "要人话说清上限：" + e.getMessage());
        verifyNoInteractions(visualAiClient);
        // 超限时**一张都不该记**（原子方法内部保证不写；这里钉住服务层也不再单独记账）
        verify(quota, never()).consumeImages(any(), any(), anyInt());
    }

    @Test
    void submitImages_quotaLedgerUnreadable_failsClosedWithHumanMessage() {
        var quota = mock(com.adaiadai.core.domain.learn.LearnQuotaRepository.class);
        when(quota.tryConsumeImages(any(), any(), anyInt(), anyInt())).thenThrow(
                new com.adaiadai.core.infrastructure.storage.StorageException("账本坏了", null));
        LearnDigestAppService svc = new LearnDigestAppService(aiClient, repository, directExecutor,
                fetchService, transcriptionService, visualAiClient, quota, null, 4096, 30);

        LearnException e = assertThrows(LearnException.class, () -> svc.submitImages("adai",
                List.of(new LearnDigestAppService.ImageInput(new byte[]{1, 2, 3}, "image/png", "p1.png")),
                null, null));

        assertTrue(e.getMessage().contains("额度记录读不出来"), e.getMessage());
        verifyNoInteractions(visualAiClient);
    }

    @Test
    void submitImages_executorRejected_refundsQuota() {
        // 2026-09-17 深审 P2：记账在 execute 之前，任务没排上必须退回——
        // 否则「什么也没得到，当天的图片额度却被耗掉」（接口写了允许负数回退，此前零调用者）。
        var quota = mock(com.adaiadai.core.domain.learn.LearnQuotaRepository.class);
        when(quota.tryConsumeImages(any(), any(), anyInt(), anyInt()))
                .thenReturn(new com.adaiadai.core.domain.learn.LearnQuotaRepository
                        .ImageQuotaResult(true, 1));
        java.util.concurrent.Executor rejecting = r -> {
            throw new java.util.concurrent.RejectedExecutionException("队列满");
        };
        LearnDigestAppService svc = new LearnDigestAppService(aiClient, repository, rejecting,
                fetchService, transcriptionService, visualAiClient, quota, null, 4096, 30);

        assertThrows(LearnException.class, () -> svc.submitImages("adai",
                List.of(new LearnDigestAppService.ImageInput(new byte[]{1, 2, 3}, "image/png", "p1.png")),
                null, null));

        org.mockito.Mockito.verify(quota).consumeImages(eq("adai"), any(), eq(-1));
    }

    @Test
    void submitImages_quotaUsesAtomicCheckAndConsume_neverReadsSeparately() {
        // P2-审查5（2026-09-17）：钉住「不再走 imagesOn 读 + 稍后 consumeImages 写」这两步——
        // 两步是两次独立加锁，并发请求会各自读到「还没超」而一起写盘（日配额超卖）。
        var quota = mock(com.adaiadai.core.domain.learn.LearnQuotaRepository.class);
        when(quota.tryConsumeImages(any(), any(), anyInt(), anyInt()))
                .thenReturn(new com.adaiadai.core.domain.learn.LearnQuotaRepository
                        .ImageQuotaResult(true, 1));
        java.util.concurrent.Executor rejecting = r -> {
            throw new java.util.concurrent.RejectedExecutionException("队列满");
        };
        LearnDigestAppService svc = new LearnDigestAppService(aiClient, repository, rejecting,
                fetchService, transcriptionService, visualAiClient, quota, null, 4096, 30);

        assertThrows(LearnException.class, () -> svc.submitImages("adai",
                List.of(new LearnDigestAppService.ImageInput(new byte[]{1, 2, 3}, "image/png", "p1.png")),
                null, null));

        verify(quota).tryConsumeImages(eq("adai"), any(), eq(1), eq(30));
        verify(quota, never()).imagesOn(any(), any());
    }
}
