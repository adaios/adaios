package com.adaiadai.core.kernel.search;

import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SearchService — 展示自然化测试（第一原则，REVIEW P1-W3）。
 */
class SearchServiceTest {

    private ContentRecord record(String id, String type, String title, String content, String summary) {
        return new ContentRecord(id, type, "user_input", title, content, List.of(), LocalDateTime.now(),
                "log", summary, "life");
    }

    @Test
    void search_imageQa_naturalized() {
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of(
                record("qa1", "image_qa", "【多图问答】",
                        "【多图问答】\n图片记录：img1\n问：这是什么品牌\n答：苹果",
                        "苹果")));
        SearchService service = new SearchService(records);

        List<SearchResult> results = service.search("adai", "品牌");
        assertEquals(1, results.size());
        assertEquals("这是什么品牌", results.get(0).title(), "image_qa 搜索标题=用户问句（自然化）");
        assertFalse(results.get(0).content().contains("问："), "搜索片段不得含「问：」标签");
        assertFalse(results.get(0).content().contains("图片记录"), "搜索片段不得含「图片记录」标签");
    }

    @Test
    void search_image_naturalized() {
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of(
                record("img1", "image", "【备注】这是什么品牌呢",
                        "【图片文字】银色苹果笔记本\n【备注】这是什么品牌呢",
                        "银色苹果笔记本电脑外观")));
        SearchService service = new SearchService(records);

        List<SearchResult> results = service.search("adai", "品牌");
        assertEquals(1, results.size());
        assertEquals("银色苹果笔记本电脑外观", results.get(0).title(), "image 搜索标题=VLM 总结");
        assertFalse(results.get(0).content().contains("【备注】"), "搜索片段不得含【备注】标签");
    }
}
