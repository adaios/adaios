package com.adaiadai.core.kernel.context.engine;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextPackage 单元测试。
 * 验证上下文包的组合、简单工厂、Token 预估逻辑。
 */
class ContextPackageTest {

    @Test
    void createFullContextPackage() {
        ContextPackage ctx = new ContextPackage(
                "trading",
                "用户：张三，偏好：价值投资",
                "买入立昂微",
                "今天早盘买入立昂微 100 股，价格 25 元。",
                List.of("投资", "半导体"),
                List.of("昨天讨论过半导体行业前景"),
                "组装后的完整 Prompt",
                LocalDateTime.of(2026, 7, 18, 10, 0)
        );

        assertEquals("trading", ctx.scene());
        assertEquals("买入立昂微", ctx.recordTitle());
        assertEquals(1, ctx.relatedRefs().size());
    }

    @Test
    void simpleFactory() {
        ContextPackage ctx = ContextPackage.simple(
                "life", "用户：默认",
                "跑步", "今天跑了 5 公里", List.of("运动"),
                "分析这条记录"
        );

        assertEquals("life", ctx.scene());
        assertTrue(ctx.relatedRefs().isEmpty());
        assertNotNull(ctx.assembledAt());
    }

    @Test
    void estimateTokens_withFullContent() {
        ContextPackage ctx = ContextPackage.simple(
                "note", "用户A",
                "标题", "内容正文", List.of("标签"),
                "这是一段提示词文本"
        );
        // token 预估 = 总字符 / 2
        int expected = ("用户A" + "内容正文" + "这是一段提示词文本").length() / 2;
        assertEquals(expected, ctx.estimateTokens());
    }

    @Test
    void estimateTokens_emptyIdentity() {
        ContextPackage ctx = ContextPackage.simple(
                "note", "",
                "标题", "内容", List.of(),
                "提示词"
        );
        int expected = ("内容" + "提示词").length() / 2;
        assertEquals(expected, ctx.estimateTokens());
    }

    @Test
    void estimateTokens_chineseOnly() {
        ContextPackage ctx = ContextPackage.simple(
                "note", "我",
                "标题", "今天天气很好，适合出门散步。中午吃了面条。",
                List.of(),
                "请分析这条记录"
        );
        assertTrue(ctx.estimateTokens() > 0);
    }

    @Test
    void fieldsAreImmutable() {
        ContextPackage ctx = ContextPackage.simple(
                "note", "用户",
                "标题", "内容", List.of("标签1", "标签2"),
                "Prompt"
        );
        // recordTags 应该不可修改
        assertThrows(UnsupportedOperationException.class, () -> ctx.recordTags().add("新标签"));
    }

    @Test
    void scene_canBeNull() {
        // 理论上应该允许 null 场景
        ContextPackage ctx = new ContextPackage(
                null, "用户",
                "标题", "内容", List.of(),
                List.of(), "Prompt",
                LocalDateTime.now()
        );
        assertNull(ctx.scene());
    }

    @Test
    void multipleRelatedRefs() {
        ContextPackage ctx = new ContextPackage(
                "research", "用户",
                "调研", "内容", List.of(),
                List.of("参考1", "参考2", "参考3"),
                "Prompt",
                LocalDateTime.now()
        );
        assertEquals(3, ctx.relatedRefs().size());
    }
}
