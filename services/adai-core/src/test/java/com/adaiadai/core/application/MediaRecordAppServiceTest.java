package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MediaRecordAppService — 图片记录编排测试。
 * 真实存储（InMemoryFileStorage）+ mock VisualAiClient。
 */
class MediaRecordAppServiceTest {

    private final InMemoryFileStorage fs = new InMemoryFileStorage();
    private final RecordFileRepository recordRepository = new RecordFileRepository(fs);
    private final MemoryService memoryService = new MemoryService(fs);
    private final CardFileRepository cardRepository = new CardFileRepository(fs);

    private byte[] png() {
        // 任意字节作为"图片"（测试不校验内容，只校验流转）
        return new byte[]{1, 2, 3, 4, 5};
    }

    @Test
    void recordImage_success_persistsRecordAndMemoryAndMedia() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "持仓截图", "trading", "浦发银行 1000股", List.of("交易", "持仓")));
        PluginService pluginService = mock(PluginService.class);
        // 有 trading 插件用户：gateDomain 透传（"default" 测试账号 = 插件用户）
        when(pluginService.gateDomain(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, pluginService, mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult result = service.recordImage("default", png(), "image/png", "加仓");

        assertTrue(result.recordId().startsWith("rec_"));
        assertEquals("持仓截图", result.summary());
        assertTrue(result.mediaPath().contains("/media/"));
        assertTrue(result.mediaPath().endsWith(".png"));

        // 记录已沉淀（type=image，content 含 OCR 文本 + 备注）
        Optional<ContentRecord> record = recordRepository.findById("default", result.recordId());
        assertTrue(record.isPresent());
        assertEquals("image", record.get().type());
        assertEquals("trading", record.get().domain());
        assertTrue(record.get().content().contains("浦发银行 1000股"));
        assertTrue(record.get().content().contains("加仓"));

        // 记忆已沉淀
        Optional<Memory> memory = memoryService.findByRecordId("default", result.recordId());
        assertTrue(memory.isPresent());
        assertEquals("持仓截图", memory.get().summary());

        // 图片二进制已落盘
        byte[] stored = fs.readBytes("default", result.mediaPath());
        assertArrayEquals(png(), stored);
    }

    @Test
    void recordImage_vlmFailure_degradedWithCaption() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenThrow(new RuntimeException("GLM 服务不可用"));
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult result = service.recordImage("default", png(), "image/png", "会议白板");

        // 数据不丢：记录用备注降级
        assertEquals("会议白板", result.summary());
        Optional<ContentRecord> record = recordRepository.findById("default", result.recordId());
        assertTrue(record.isPresent(), "VLM 失败也应保存记录");
    }

    @Test
    void recordImage_vlmFailure_noCaption_fallbackText() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenThrow(new RuntimeException("down"));
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult result = service.recordImage("default", png(), "image/jpeg", null);

        assertEquals("图片记录", result.summary());
        assertTrue(result.mediaPath().endsWith(".jpg"));
    }

    @Test
    void recordImage_notImage_throws() {
        MediaRecordAppService service = new MediaRecordAppService(
                mock(VisualAiClient.class), recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        assertThrows(IllegalArgumentException.class,
                () -> service.recordImage("default", png(), "text/plain", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordImage("default", png(), null, null));
    }

    @Test
    void recordImage_tooLarge_throws() {
        MediaRecordAppService service = new MediaRecordAppService(
                mock(VisualAiClient.class), recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));
        byte[] huge = new byte[5 * 1024 * 1024 + 1];

        assertThrows(IllegalArgumentException.class,
                () -> service.recordImage("default", huge, "image/png", null));
    }

    @Test
    void askImage_success_persistsQaRecordAndReturnsAnswer() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "持仓截图", "trading", "浦发银行", List.of("交易")));
        when(glm.ask(any(), any())).thenReturn("这是浦发银行，持仓约 1000 股。");
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult img = service.recordImage("default", png(), "image/png", null);

        MediaRecordAppService.AskResult result = service.askImage("default", img.recordId(), "这是什么股票？");

        assertEquals("这是浦发银行，持仓约 1000 股。", result.answer());
        assertEquals(img.recordId(), result.imageRecordId());

        // 问答已沉淀为 image_qa 记录（content 含问题/回答/图片溯源）
        Optional<ContentRecord> qa = recordRepository.findById("default", result.recordId());
        assertTrue(qa.isPresent());
        assertEquals("image_qa", qa.get().type());
        assertEquals("question", qa.get().intent());
        assertTrue(qa.get().content().contains("这是什么股票？"));
        assertTrue(qa.get().content().contains("浦发银行"));
        assertTrue(qa.get().content().contains(img.recordId()));

        // #209：追问历史已持久化到图片卡 card 文件（id=图片记录 id）——刷新后仍挂在图片卡下
        Optional<com.adaiadai.core.kernel.record.CardRecord> card = cardRepository.findById("default", img.recordId());
        assertTrue(card.isPresent(), "图片追问应追加到图片卡 card 文件");
        assertEquals(2, card.get().turns().size(), "第一轮追问应含 Q + A 两条 turns");
        assertTrue(card.get().turns().get(0).isUser());
        assertEquals("这是什么股票？", card.get().turns().get(0).text());
        assertFalse(card.get().turns().get(1).isUser());
        assertEquals("这是浦发银行，持仓约 1000 股。", card.get().turns().get(1).text());
    }

    @Test
    void askImage_multipleRounds_appendsToCardTurns() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "持仓截图", "trading", "浦发银行", List.of("交易")));
        when(glm.ask(any(), any())).thenReturn("这是浦发银行。");
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult img = service.recordImage("default", png(), "image/png", null);

        // 两轮追问 → 同一图片卡 card 文件 turns 追加为 4 条
        service.askImage("default", img.recordId(), "这是什么股票？");
        service.askImage("default", img.recordId(), "现在能买吗？");

        Optional<com.adaiadai.core.kernel.record.CardRecord> card = cardRepository.findById("default", img.recordId());
        assertTrue(card.isPresent());
        assertEquals(4, card.get().turns().size(), "两轮追问应追加为 4 条 turns");
        assertEquals("这是什么股票？", card.get().turns().get(0).text());
        assertEquals("现在能买吗？", card.get().turns().get(2).text());
    }

    @Test
    void askImage_blankQuestion_throws() {
        MediaRecordAppService service = new MediaRecordAppService(
                mock(VisualAiClient.class), recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.askImage("default", "rec_x", "   "));
        assertThrows(IllegalArgumentException.class,
                () -> service.askImage("default", "rec_x", null));
    }

    @Test
    void askImage_unknownImage_throws() {
        MediaRecordAppService service = new MediaRecordAppService(
                mock(VisualAiClient.class), recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.askImage("default", "rec_unknown", "这是什么？"));
    }

    @Test
    void mediaPathFor_returnsSavedPath() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding("图", "photo", "", List.of()));
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult result = service.recordImage("default", png(), "image/png", null);

        assertEquals(Optional.of(result.mediaPath()),
                service.mediaPathFor("default", result.recordId()));
        assertTrue(service.mediaPathFor("default", "rec_unknown").isEmpty());
    }

    // ── askImages（Phase 1 带图 ask：多图一次问答）──

    private MediaRecordAppService.MediaRecordResult[] uploadN(MediaRecordAppService service, int n) {
        MediaRecordAppService.MediaRecordResult[] arr = new MediaRecordAppService.MediaRecordResult[n];
        for (int i = 0; i < n; i++) {
            arr[i] = service.recordImage("default", png(), "image/png", null);
        }
        return arr;
    }

    @Test
    void askImages_success_persistsQaRecordAndFirstCardTurns() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "持仓截图", "trading", "浦发银行", List.of("交易")));
        when(glm.askMulti(any(), any())).thenReturn("左图是持仓，右图是走势。");
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult[] imgs = uploadN(service, 2);

        MediaRecordAppService.AskBatchResult result = service.askImages("default",
                List.of(imgs[0].recordId(), imgs[1].recordId()), "这两张图分别是什么？");

        assertEquals("question", result.intent());
        assertEquals("左图是持仓，右图是走势。", result.answer());
        assertEquals(2, result.imageRecordIds().size());

        // 多图问答沉淀为 image_qa 记录（content 引用全部图片 id）
        Optional<ContentRecord> qa = recordRepository.findById("default", result.recordId());
        assertTrue(qa.isPresent());
        assertEquals("image_qa", qa.get().type());
        assertTrue(qa.get().content().contains(imgs[0].recordId()));
        assertTrue(qa.get().content().contains(imgs[1].recordId()));
        assertTrue(qa.get().content().contains("这两张图分别是什么？"));

        // Q/A 追加到首图卡（刷新后首图卡显示问答气泡）
        Optional<com.adaiadai.core.kernel.record.CardRecord> card =
                cardRepository.findById("default", imgs[0].recordId());
        assertTrue(card.isPresent(), "多图问答应追加到首图卡 card 文件");
        assertEquals(2, card.get().turns().size());
        assertTrue(card.get().turns().get(0).isUser());
        assertEquals("这两张图分别是什么？", card.get().turns().get(0).text());
        assertFalse(card.get().turns().get(1).isUser());
    }

    @Test
    void askImages_overMaxImages_throws() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding("图", "photo", "", List.of()));
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult[] imgs = uploadN(service, 4);
        List<String> ids = java.util.Arrays.stream(imgs).map(MediaRecordAppService.MediaRecordResult::recordId).toList();

        // 4 张超上限 3 → 抛异常，不调 VLM
        assertThrows(IllegalArgumentException.class,
                () -> service.askImages("default", ids, "这是什么？"));
    }

    @Test
    void askImages_blankQuestion_throws() {
        VisualAiClient glm = mock(VisualAiClient.class);
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        assertThrows(IllegalArgumentException.class,
                () -> service.askImages("default", List.of("rec_x"), "   "));
        assertThrows(IllegalArgumentException.class,
                () -> service.askImages("default", List.of("rec_x"), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.askImages("default", null, "问题"));
    }

    @Test
    void askImages_unknownImage_throws() {
        VisualAiClient glm = mock(VisualAiClient.class);
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        assertThrows(IllegalArgumentException.class,
                () -> service.askImages("default", List.of("rec_unknown"), "这是什么？"));
    }

    @Test
    void recordImage_domainGatedByPluginService() {
        // REVIEW P1-B4：VLM 判 category=trading，但用户无 trading 插件 → 落盘 domain 收敛 life
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "持仓截图", "trading", "浦发银行 1000股", List.of("交易", "持仓")));
        PluginService pluginService = mock(PluginService.class);
        when(pluginService.gateDomain(eq("alice"), eq("trading"))).thenReturn("life");

        MediaRecordAppService service = new MediaRecordAppService(
                glm, recordRepository, memoryService, fs, cardRepository, pluginService,
                mock(TradeLogCollectService.class));
        service.recordImage("alice", png(), "image/png", "加仓");

        // 从真实存储读回记录，验证 domain 已收敛（不得落盘 trading 标注）
        List<ContentRecord> all = recordRepository.findAll("alice");
        assertEquals(1, all.size());
        assertEquals("life", all.get(0).domain(), "无插件用户图片记录不得落盘 trading 标注");
        verify(pluginService).gateDomain("alice", "trading");
    }

    // ── S-2 聚合卡身份断裂修复：image_qa 记录解析（mediaPathFor / referencedImageIdsOf）──

    @Test
    void mediaPathFor_imageQa_resolvesFirstImage() {
        // S-2：image_qa 聚合记录本身无媒体文件（{id}.{ext} 不存在）→ 回退解析引用首图
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "持仓截图", "trading", "浦发银行", List.of("交易")));
        when(glm.askMulti(any(), any())).thenReturn("左图是持仓，右图是走势。");
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult[] imgs = uploadN(service, 2);
        MediaRecordAppService.AskBatchResult qa = service.askImages("default",
                List.of(imgs[0].recordId(), imgs[1].recordId()), "这两张图分别是什么？");

        // image_qa id 本身找不到 {id}.{ext} 文件，但 mediaPathFor 应回退返回首图路径（修复缩略图 404）
        assertTrue(service.mediaPathFor("default", imgs[0].recordId()).isPresent(),
                "image 记录原语义：直读媒体文件");
        Optional<String> qaPath = service.mediaPathFor("default", qa.recordId());
        assertTrue(qaPath.isPresent(), "image_qa 记录应回退解析到首图 mediaPath（修复 404）");
        assertEquals(imgs[0].mediaPath(), qaPath.get(), "image_qa 缩略图 = 引用首图的媒体文件");
    }

    @Test
    void mediaPathFor_imageQa_missingFirstImage_returnsEmpty() {
        // 引用的图片记录不存在（数据损坏/被删）→ 回退解析不到 → 空（404，不误指）
        VisualAiClient glm = mock(VisualAiClient.class);
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        ContentRecord qa = new ContentRecord(
                "rec_20260815_000000001", "image_qa", "ai_answer",
                "问", "【多图问答】\n图片记录：rec_missing\n问：这是什么\n答：是", List.of(),
                java.time.LocalDateTime.of(2026, 8, 15, 10, 0), "question", "是", "life");
        recordRepository.save("default", qa);

        assertTrue(service.mediaPathFor("default", qa.id()).isEmpty(),
                "引用图无媒体文件 → image_qa 也返回空");
    }

    @Test
    void referencedImageIdsOf_imageQa_returnsAllReferencedIds() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding("图", "photo", "", List.of()));
        when(glm.askMulti(any(), any())).thenReturn("综合回答");
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult[] imgs = uploadN(service, 3);
        MediaRecordAppService.AskBatchResult qa = service.askImages("default",
                List.of(imgs[0].recordId(), imgs[1].recordId(), imgs[2].recordId()), "三张分别是什么？");

        List<String> refs = service.referencedImageIdsOf("default", qa.recordId());
        assertEquals(List.of(imgs[0].recordId(), imgs[1].recordId(), imgs[2].recordId()), refs,
                "image_qa 聚合记录 → 解析出全部引用图 id（追问路由/多图上下文用）");
    }

    @Test
    void referencedImageIdsOf_nonImageQa_returnsEmpty() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding("图", "photo", "", List.of()));
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository, mock(PluginService.class), mock(TradeLogCollectService.class));

        MediaRecordAppService.MediaRecordResult img = service.recordImage("default", png(), "image/png", null);

        assertTrue(service.referencedImageIdsOf("default", img.recordId()).isEmpty(),
                "普通 image 记录无引用 → 空（走单图追问）");
        assertTrue(service.referencedImageIdsOf("default", "rec_unknown").isEmpty(),
                "未知 id → 空");
    }
}
