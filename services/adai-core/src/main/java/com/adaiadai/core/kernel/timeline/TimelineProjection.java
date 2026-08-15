package com.adaiadai.core.kernel.timeline;

import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TimelineProjection — Record 的时间序列投影。
 * <p>
 * 将 Record 按时间维度组织为时间线。
 * Timeline 不是独立实体，而是 Record 的查询投影，自动从 Record 文件生成。
 * <p>
 * 展示层聚合（一次输入 = 一个事件，S-2 产品决策）：
 * <ul>
 *   <li><b>多轮 chat 聚合</b>：卡片多轮对话的每轮问答记录（intent=question 且 content 与卡片
 *       turn 文本一致）不单独成条——只保留会话首条，避免时间线 N 轮 N 条</li>
 *   <li><b>带图 ask 聚合</b>：{@code image_qa} 记录 content 引用（「图片记录：{ids}」）的 image
 *       记录不单独成条——合并为一个图文事件，缩略图取引用首图</li>
 * </ul>
 */
@Component
public class TimelineProjection {

    private static final Logger log = LoggerFactory.getLogger(TimelineProjection.class);

    /** 与 Feed 的 turn 匹配同口径：content 截断 60 字符（避免长文本误匹配/漏匹配）。 */
    private static final int MAX_TURN_KEY = 60;

    /** image_qa content 中的图片引用（freeze §2.1：`图片记录：{id1}, {id2}`，逗号+空格分隔）。 */
    private static final Pattern IMAGE_REF = Pattern.compile("图片记录[：:]([^\\n]+)");

    private final RecordRepository recordRepository;
    private final CardFileRepository cardRepository;

    public TimelineProjection(RecordRepository recordRepository, CardFileRepository cardRepository) {
        this.recordRepository = recordRepository;
        this.cardRepository = cardRepository;
    }

    /**
     * 获取该用户完整时间线（按时间倒序，展示层聚合后）。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @return 时间线条目列表
     */
    public List<TimelineEntry> fullTimeline(String userId) {
        List<ContentRecord> all = recordRepository.findAll(userId);
        List<CardRecord> cards = cardRepository.findAll(userId);
        // 多轮 chat：同一会话只保留首问记录，其余轮次记录过滤（时间线单条，详情在卡片）
        Set<String> chatDropIds = collectChatTurnDropIds(all, cards);
        // 带图 ask：image_qa 引用的 image 记录仅当**同一天**才聚合（P1-B2：跨天传图与追问是两个输入）
        Map<String, LocalDate> qaReferencedImageDates = collectQaReferencedImageDates(all);

        return all.stream()
                .filter(r -> !chatDropIds.contains(r.id()))
                .filter(r -> !("image".equals(r.type())
                        && qaReferencedImageDates.containsKey(r.id())
                        && qaReferencedImageDates.get(r.id()).equals(r.createdAt().toLocalDate())))
                .map(r -> toEntry(userId, r))
                .sorted((a, b) -> b.dateTime().compareTo(a.dateTime()))
                .toList();
    }

    /**
     * 获取指定类型的时间线。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param type   记录类型
     * @return 过滤后的时间线
     */
    public List<TimelineEntry> timelineByType(String userId, String type) {
        return fullTimeline(userId).stream()
                .filter(e -> type.equals(e.type()))
                .toList();
    }

    /**
     * 获取该用户最近 N 条时间线条目。
     *
     * @param userId 用户 ID（单用户传 "default"）
     * @param limit  数量上限
     * @return 最近的 N 条
     */
    public List<TimelineEntry> recent(String userId, int limit) {
        return fullTimeline(userId).stream()
                .limit(limit)
                .toList();
    }

    // ── 聚合辅助 ──

    /**
     * 多轮 chat 聚合：同一会话的轮次记录只保留首问（时间最早），其余过滤——时间线单条。
     * <p>
     * REVIEW P1-B3 边界：
     * <ul>
     *   <li>只聚合 {@code intent=question} 的问答轮次记录（普通 log 记录即使文本相同也不误 drop）</li>
     *   <li>同一文本映射到多个卡片（跨会话同文本）→ 歧义保守**不聚合**（宁缺勿误删，避免会话整体消失）</li>
     * </ul>
     */
    private Set<String> collectChatTurnDropIds(List<ContentRecord> records, List<CardRecord> cards) {
        // 轮次文本 → 命中的卡片 id 集合（多卡片命中 = 歧义）
        Map<String, Set<String>> textToCards = new java.util.HashMap<>();
        for (CardRecord card : cards) {
            if (card.turns() == null) continue;
            for (CardRecord.Turn turn : card.turns()) {
                if (!turn.isUser() || turn.text() == null || turn.text().isBlank()) continue;
                textToCards.computeIfAbsent(turnKey(turn.text()), k -> new HashSet<>()).add(card.id());
            }
        }
        Map<String, List<ContentRecord>> byCard = new java.util.LinkedHashMap<>();
        for (ContentRecord r : records) {
            if (!"question".equals(r.intent())) continue; // P1-B3：仅问答轮次参与聚合
            Set<String> cardIds = textToCards.get(turnKey(r.content()));
            if (cardIds == null || cardIds.isEmpty() || cardIds.size() > 1) continue; // 无匹配/歧义 → 不聚合
            byCard.computeIfAbsent(cardIds.iterator().next(), k -> new java.util.ArrayList<>()).add(r);
        }
        Set<String> drop = new HashSet<>();
        for (List<ContentRecord> turns : byCard.values()) {
            if (turns.size() < 2) continue; // 单轮会话无需聚合
            turns.sort(java.util.Comparator.comparing(ContentRecord::createdAt));
            for (int i = 1; i < turns.size(); i++) {
                drop.add(turns.get(i).id());
            }
        }
        return drop;
    }

    private String turnKey(String content) {
        if (content == null || content.isBlank()) return "";
        String key = content.strip();
        if (key.length() > MAX_TURN_KEY) key = key.substring(0, MAX_TURN_KEY);
        return key;
    }

    /** 收集 image_qa 引用的图片 id → 引用它的 image_qa 的日期（P1-B2：跨天不聚合）。 */
    private Map<String, LocalDate> collectQaReferencedImageDates(List<ContentRecord> records) {
        Map<String, LocalDate> map = new HashMap<>();
        for (ContentRecord r : records) {
            if (!"image_qa".equals(r.type()) || r.content() == null) continue;
            Matcher m = IMAGE_REF.matcher(r.content());
            if (m.find()) {
                LocalDate qaDate = r.createdAt().toLocalDate();
                for (String id : m.group(1).split(",")) {
                    String t = id.strip();
                    if (!t.isEmpty()) map.putIfAbsent(t, qaDate);
                }
            }
        }
        return map;
    }

    // ── 条目构建 ──

    private TimelineEntry toEntry(String userId, ContentRecord record) {
        String mediaPath = null;
        if ("image".equals(record.type())) {
            mediaPath = recordRepository.findMediaPath(userId, record.id()).orElse(null);
        } else if ("image_qa".equals(record.type()) && record.content() != null) {
            // 带图 ask 聚合：缩略图取引用首图（一次输入 = 一个图文事件）
            Matcher m = IMAGE_REF.matcher(record.content());
            if (m.find()) {
                String firstId = m.group(1).split(",")[0].strip();
                mediaPath = recordRepository.findMediaPath(userId, firstId).orElse(null);
            }
        }
        return new TimelineEntry(
                record.id(),
                record.type(),
                record.title(),
                record.tags(),
                record.createdAt(),
                mediaPath
        );
    }
}
