package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository);

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
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository);

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
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository);

        MediaRecordAppService.MediaRecordResult result = service.recordImage("default", png(), "image/jpeg", null);

        assertEquals("图片记录", result.summary());
        assertTrue(result.mediaPath().endsWith(".jpg"));
    }

    @Test
    void recordImage_notImage_throws() {
        MediaRecordAppService service = new MediaRecordAppService(
                mock(VisualAiClient.class), recordRepository, memoryService, fs, cardRepository);

        assertThrows(IllegalArgumentException.class,
                () -> service.recordImage("default", png(), "text/plain", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordImage("default", png(), null, null));
    }

    @Test
    void recordImage_tooLarge_throws() {
        MediaRecordAppService service = new MediaRecordAppService(
                mock(VisualAiClient.class), recordRepository, memoryService, fs, cardRepository);
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
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository);

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
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository);

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
                mock(VisualAiClient.class), recordRepository, memoryService, fs, cardRepository);
        assertThrows(IllegalArgumentException.class,
                () -> service.askImage("default", "rec_x", "   "));
        assertThrows(IllegalArgumentException.class,
                () -> service.askImage("default", "rec_x", null));
    }

    @Test
    void askImage_unknownImage_throws() {
        MediaRecordAppService service = new MediaRecordAppService(
                mock(VisualAiClient.class), recordRepository, memoryService, fs, cardRepository);
        assertThrows(IllegalArgumentException.class,
                () -> service.askImage("default", "rec_unknown", "这是什么？"));
    }

    @Test
    void mediaPathFor_returnsSavedPath() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding("图", "photo", "", List.of()));
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService, fs, cardRepository);

        MediaRecordAppService.MediaRecordResult result = service.recordImage("default", png(), "image/png", null);

        assertEquals(Optional.of(result.mediaPath()),
                service.mediaPathFor("default", result.recordId()));
        assertTrue(service.mediaPathFor("default", "rec_unknown").isEmpty());
    }
}
