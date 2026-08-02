package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
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

    private byte[] png() {
        // 任意字节作为"图片"（测试不校验内容，只校验流转）
        return new byte[]{1, 2, 3, 4, 5};
    }

    @Test
    void recordImage_success_persistsRecordAndMemoryAndMedia() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "持仓截图", "trading", "浦发银行 1000股", List.of("交易", "持仓")));
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService);

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
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService);

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
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService);

        MediaRecordAppService.MediaRecordResult result = service.recordImage("default", png(), "image/jpeg", null);

        assertEquals("图片记录", result.summary());
        assertTrue(result.mediaPath().endsWith(".jpg"));
    }

    @Test
    void recordImage_notImage_throws() {
        MediaRecordAppService service = new MediaRecordAppService(
                mock(VisualAiClient.class), recordRepository, memoryService);

        assertThrows(IllegalArgumentException.class,
                () -> service.recordImage("default", png(), "text/plain", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordImage("default", png(), null, null));
    }

    @Test
    void recordImage_tooLarge_throws() {
        MediaRecordAppService service = new MediaRecordAppService(
                mock(VisualAiClient.class), recordRepository, memoryService);
        byte[] huge = new byte[5 * 1024 * 1024 + 1];

        assertThrows(IllegalArgumentException.class,
                () -> service.recordImage("default", huge, "image/png", null));
    }

    @Test
    void mediaPathFor_returnsSavedPath() {
        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.understand(any())).thenReturn(new ImageUnderstanding("图", "photo", "", List.of()));
        MediaRecordAppService service = new MediaRecordAppService(glm, recordRepository, memoryService);

        MediaRecordAppService.MediaRecordResult result = service.recordImage("default", png(), "image/png", null);

        assertEquals(Optional.of(result.mediaPath()),
                service.mediaPathFor("default", result.recordId()));
        assertTrue(service.mediaPathFor("default", "rec_unknown").isEmpty());
    }
}
