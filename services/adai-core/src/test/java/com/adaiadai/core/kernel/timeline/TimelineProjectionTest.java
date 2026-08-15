package com.adaiadai.core.kernel.timeline;

import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TimelineProjection — 展示层聚合测试（S-2 产品决策：一次输入 = 一个事件）。
 * 覆盖：多轮 chat 聚合为单条 / 带图 ask（image_qa）聚合为图文事件 / 普通记录不过滤。
 */
class TimelineProjectionTest {

    private final RecordRepository records = mock(RecordRepository.class);
    private final CardFileRepository cards = mock(CardFileRepository.class);

    private ContentRecord record(String id, String type, String title, String content, String intent,
                                 LocalDateTime time) {
        return new ContentRecord(id, type, "user_input", title, content, List.of(), time, intent, title, "life");
    }

    @Test
    void fullTimeline_aggregatesChatTurns_keepsOnlyFirstQuestion() {
        // 一张卡片含 3 轮对话（首问 + 追问 ×2），对应 3 条 intent=question 记录
        CardRecord card = new CardRecord("card_1", "conversation", "active",
                List.of(), List.of(
                        new CardRecord.Turn(true, "第一问", "09:00"),
                        new CardRecord.Turn(false, "第一答", "09:00"),
                        new CardRecord.Turn(true, "第二问", "09:01"),
                        new CardRecord.Turn(false, "第二答", "09:01"),
                        new CardRecord.Turn(true, "第三问", "09:02"),
                        new CardRecord.Turn(false, "第三答", "09:02")
                ), null, LocalDateTime.of(2026, 8, 15, 9, 0),
                LocalDateTime.of(2026, 8, 15, 9, 2));
        when(cards.findAll(any())).thenReturn(List.of(card));

        LocalDateTime t0 = LocalDateTime.of(2026, 8, 15, 9, 0);
        when(records.findAll(any())).thenReturn(List.of(
                record("q1", "note", "第一问", "第一问", "question", t0),
                record("q2", "note", "第二问", "第二问", "question", t0.plusMinutes(1)),
                record("q3", "note", "第三问", "第三问", "question", t0.plusMinutes(2)),
                record("n1", "note", "普通记录", "普通记录", "log", t0.plusMinutes(3))
        ));

        TimelineProjection projection = new TimelineProjection(records, cards);
        List<TimelineEntry> timeline = projection.fullTimeline("adai");

        // 多轮 chat：3 条问答记录 → 只保留首问 1 条；普通记录保留
        assertEquals(2, timeline.size(), "3 轮问答 + 1 普通记录 → 聚合后 2 条（首问 + 普通记录）");
        assertEquals("q1", timeline.get(1).id(), "保留首问记录（时间最早）");
        assertEquals("n1", timeline.get(0).id(), "普通记录不受影响");
    }

    @Test
    void fullTimeline_aggregatesImageQa_keepsSingleImageQaEntryWithFirstImage() {
        // 带图 ask：3 张图 + 1 条 image_qa（引用 3 图）→ 聚合为 1 条图文事件
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 15, 10, 0);
        when(records.findAll(any())).thenReturn(List.of(
                record("img1", "image", "图1", "【图片文字】K线", "log", t0),
                record("img2", "image", "图2", "【图片文字】成交量", "log", t0),
                record("img3", "image", "图3", "【图片文字】MACD", "log", t0),
                record("qa1", "image_qa", "顶背离判断",
                        "【多图问答】\n图片记录：img1, img2, img3\n问：看看是不是顶背离\n答：...",
                        "question", t0.plusSeconds(30))
        ));
        when(records.findMediaPath(eq("adai"), eq("img1"))).thenReturn(Optional.of("records/2026/08/media/img1.png"));

        TimelineProjection projection = new TimelineProjection(records, cards);
        List<TimelineEntry> timeline = projection.fullTimeline("adai");

        assertEquals(1, timeline.size(), "3 图 + 1 问答 → 聚合为 1 条图文事件");
        assertEquals("qa1", timeline.get(0).id(), "保留 image_qa 记录");
        assertEquals("image_qa", timeline.get(0).type());
        assertEquals("records/2026/08/media/img1.png", timeline.get(0).mediaPath(),
                "图文事件缩略图取引用首图");
    }

    @Test
    void fullTimeline_keepsOrdinaryRecords_untouched() {
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 15, 8, 0);
        when(records.findAll(any())).thenReturn(List.of(
                record("n1", "note", "记录一", "记录一", "log", t0),
                record("n2", "note", "记录二", "记录二", "log", t0.plusMinutes(5)),
                record("c1", "conversation", "对话摘要", "对话摘要", "log", t0.plusMinutes(10))
        ));
        when(cards.findAll(any())).thenReturn(List.of());

        TimelineProjection projection = new TimelineProjection(records, cards);
        List<TimelineEntry> timeline = projection.fullTimeline("adai");

        assertEquals(3, timeline.size(), "无聚合场景：全部记录原样输出");
        assertNull(timeline.get(0).mediaPath(), "普通记录无媒体路径");
    }

    @Test
    void fullTimeline_crossDayImageQa_notAggregated_imageKept() {
        // REVIEW P1-B2：昨天（8/14）传图、今天（8/15）追问——跨天是两个输入，
        // 历史图片事件必须保留（不得被今天的 image_qa 聚合吞掉）
        LocalDateTime imgTime = LocalDateTime.of(2026, 8, 14, 10, 0);
        LocalDateTime qaTime = LocalDateTime.of(2026, 8, 15, 9, 0);
        when(records.findAll(any())).thenReturn(List.of(
                record("img1", "image", "图1", "【图片文字】K线", "log", imgTime),
                record("qa1", "image_qa", "顶背离判断",
                        "【多图问答】\n图片记录：img1\n问：看看是不是顶背离\n答：是",
                        "question", qaTime)
        ));

        TimelineProjection projection = new TimelineProjection(records, cards);
        List<TimelineEntry> timeline = projection.fullTimeline("adai");

        assertEquals(2, timeline.size(), "跨天引用：图与问答各自独立成条（跨天是两个输入）");
        assertTrue(timeline.stream().anyMatch(e -> "image".equals(e.type())),
                "跨天时历史图片事件不得被过滤消失");
    }

    @Test
    void fullTimeline_crossSessionSameText_ambiguousNotDropped() {
        // REVIEW P1-B3：两个会话都有相同轮次文本（"帮我总结一下"）→ 歧义保守不聚合，
        // 两个会话的记录都保留（不得归并错误桶导致某个会话整体消失）
        CardRecord cardA = new CardRecord("card_a", "conversation", "active",
                List.of(), List.of(
                        new CardRecord.Turn(true, "帮我总结一下", "10:00"),
                        new CardRecord.Turn(false, "好的", "10:00")),
                null, LocalDateTime.of(2026, 8, 15, 10, 0), null);
        CardRecord cardB = new CardRecord("card_b", "conversation", "active",
                List.of(), List.of(
                        new CardRecord.Turn(true, "帮我总结一下", "14:00"),
                        new CardRecord.Turn(false, "好的", "14:00")),
                null, LocalDateTime.of(2026, 8, 15, 14, 0), null);
        when(cards.findAll(any())).thenReturn(List.of(cardA, cardB));

        LocalDateTime t0 = LocalDateTime.of(2026, 8, 15, 10, 0);
        when(records.findAll(any())).thenReturn(List.of(
                record("qa", "note", "帮我总结一下", "帮我总结一下", "question", t0),
                record("qb", "note", "帮我总结一下", "帮我总结一下", "question", t0.plusHours(4))
        ));

        TimelineProjection projection = new TimelineProjection(records, cards);
        List<TimelineEntry> timeline = projection.fullTimeline("adai");

        assertEquals(2, timeline.size(),
                "跨会话同文本 → 歧义保守不聚合，两条记录都保留（宁缺勿误删）");
    }

    @Test
    void fullTimeline_logRecordMatchingTurnText_notDropped() {
        // REVIEW P1-B3：普通 log 记录内容恰与某卡片轮次文本相同 → 不参与 chat 聚合，不被误 drop
        CardRecord card = new CardRecord("card_1", "conversation", "active",
                List.of(), List.of(
                        new CardRecord.Turn(true, "今天天气怎么样", "09:00"),
                        new CardRecord.Turn(false, "晴天", "09:00")),
                null, LocalDateTime.of(2026, 8, 15, 9, 0), null);
        when(cards.findAll(any())).thenReturn(List.of(card));

        LocalDateTime t0 = LocalDateTime.of(2026, 8, 15, 9, 0);
        when(records.findAll(any())).thenReturn(List.of(
                record("q1", "note", "今天天气怎么样", "今天天气怎么样", "question", t0),
                record("n1", "note", "今天天气怎么样", "今天天气怎么样", "log", t0.plusMinutes(1))
        ));

        TimelineProjection projection = new TimelineProjection(records, cards);
        List<TimelineEntry> timeline = projection.fullTimeline("adai");

        assertEquals(2, timeline.size(),
                "log 记录即使文本与轮次相同也不参与 chat 聚合（intent 过滤）");
        assertTrue(timeline.stream().anyMatch(e -> e.id().equals("n1")),
                "普通 log 记录不得被误 drop");
    }
}
