package com.adaiadai.core.kernel.record;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
