package com.adaiadai.core.kernel.record;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImageQaFormatter — image_qa 展示自然化测试（第一原则：无第三视角）。
 */
class ImageQaFormatterTest {

    @Test
    void naturalize_multiImage_format() {
        String[] result = ImageQaFormatter.naturalize(
                "【多图问答】\n图片记录：img1, img2\n问：看看是不是顶背离\n答：是，短线见顶");
        assertArrayEquals(new String[]{"看看是不是顶背离", "看看是不是顶背离\n是，短线见顶"}, result,
                "标题=问句，正文=问/答两行（去【多图问答】/图片记录/问：/答：标签）");
    }

    @Test
    void naturalize_singleImage_format() {
        String[] result = ImageQaFormatter.naturalize(
                "【图片问答】\n图片记录：rec_1\n问：这是什么品牌\n答：苹果");
        assertArrayEquals(new String[]{"这是什么品牌", "这是什么品牌\n苹果"}, result);
    }

    @Test
    void naturalizeImage_stripsLabels() {
        assertEquals("这是什么品牌呢", ImageQaFormatter.naturalizeImage("【备注】这是什么品牌呢"),
                "【备注】标签去掉，保留用户的话");
        assertEquals("银色苹果笔记本", ImageQaFormatter.naturalizeImage("【图片文字】银色苹果笔记本"),
                "【图片文字】标签去掉");
        assertEquals("K线图\nMACD 金叉", ImageQaFormatter.naturalizeImage("【图片文字】K线图\n【备注】MACD 金叉"),
                "多个标签行全部去掉，内容保留");
        assertNull(ImageQaFormatter.naturalizeImage(null));
    }

    @Test
    void naturalize_noQuestion_returnsNull() {
        assertNull(ImageQaFormatter.naturalize("普通记录内容"), "无「问：」→ 非 image_qa 格式，返回 null");
        assertNull(ImageQaFormatter.naturalize(null));
    }

    // ── imageRecordIds（S-2 聚合卡身份解析共享契约）──

    @Test
    void imageRecordIds_multiImage_extractsAllInOrder() {
        List<String> ids = ImageQaFormatter.imageRecordIds(
                "【多图问答】\n图片记录：img1, img2, img3\n问：看看是不是顶背离\n答：是");
        assertEquals(List.of("img1", "img2", "img3"), ids, "保序提取全部引用图 id（去空白）");
    }

    @Test
    void imageRecordIds_singleImageFormat() {
        assertEquals(List.of("rec_1"),
                ImageQaFormatter.imageRecordIds("【图片问答】\n图片记录：rec_1\n问：这是什么品牌\n答：苹果"));
    }

    @Test
    void imageRecordIds_noReference_returnsEmpty() {
        assertTrue(ImageQaFormatter.imageRecordIds("【多图问答】\n问：这是什么\n答：是").isEmpty(),
                "无「图片记录：」行 → 空列表");
        assertTrue(ImageQaFormatter.imageRecordIds(null).isEmpty());
        assertTrue(ImageQaFormatter.imageRecordIds("").isEmpty());
        assertTrue(ImageQaFormatter.imageRecordIds("【多图问答】\n图片记录：\n问：x\n答：y").isEmpty(),
                "引用行为空 → 空列表");
    }

    // ── parseTurns（image_qa 条目对话历史）──

    @Test
    void parseTurns_multiImage_returnsQuestionAndAnswerTurns() {
        List<CardRecord.Turn> turns = ImageQaFormatter.parseTurns(
                "【多图问答】\n图片记录：img1, img2\n问：看看是不是顶背离\n答：是，短线见顶", "10:00");
        assertEquals(2, turns.size());
        assertTrue(turns.get(0).isUser());
        assertEquals("看看是不是顶背离", turns.get(0).text());
        assertEquals("10:00", turns.get(0).time());
        assertTrue(!turns.get(1).isUser());
        assertEquals("是，短线见顶", turns.get(1).text());
        assertEquals("10:00", turns.get(1).time(), "问/答同刻发生，时间一致");
    }

    @Test
    void parseTurns_emptyAnswer_keepsQuestionTurnOnly() {
        List<CardRecord.Turn> turns = ImageQaFormatter.parseTurns(
                "【图片问答】\n图片记录：rec_1\n问：这是什么\n答：  ", "09:30");
        assertEquals(1, turns.size(), "回答为空 → 只保留问句 turn");
        assertTrue(turns.get(0).isUser());
        assertEquals("这是什么", turns.get(0).text());
    }

    @Test
    void parseTurns_noQuestion_returnsNull() {
        assertNull(ImageQaFormatter.parseTurns("普通记录内容", "10:00"), "无「问：」→ null（保持无 turns）");
        assertNull(ImageQaFormatter.parseTurns(null, "10:00"));
        assertNull(ImageQaFormatter.parseTurns("【多图问答】\n图片记录：img1\n问：  \n答：是", "10:00"),
                "问句为空 → null");
    }
}
