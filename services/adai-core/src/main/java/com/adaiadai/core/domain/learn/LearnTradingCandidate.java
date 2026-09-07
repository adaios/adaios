package com.adaiadai.core.domain.learn;

import java.time.LocalDate;
import java.util.List;

/**
 * LearnTradingCandidate — learn → trading 反哺候选（RFC 20260829 3.5③ + 3.6，V2 批 3）。
 * <p>
 * type=trading 且 trade_related=true 的 learn 卡片，用户确认后生成一条「建议卡」：
 * 只存提炼建议 + {@code learn_card_id} 回链，不复制整篇卡片（跨域无双写）。
 * 候选落 {@code data/{userId}/trading/candidates/}，供用户在交易知识库工作流审核后
 * 融合归正式规则目录（对齐「规则改动必须过用户审核闸」红线，P1-交易9 教训）。
 *
 * @param title       候选标题（= 源卡片标题，文件命名锚点）
 * @param learnCardId 回链：源 learn 卡片相对路径（learn/{type}/{yyyy-MM-dd}_{title}.md）
 * @param sourceType  源卡片 type（应为 trading）
 * @param created     候选生成日期（服务器日期，非源卡片消化日）
 * @param coreView    建议核心（提炼自源卡片核心观点）
 * @param keyPoints   建议要点（提炼自源卡片关键要点）
 * @param tradeNote   与既有 R 规则的关系备注（互补/冲突/重复，源自源卡片）
 * @param tags        标签（源自源卡片）
 */
public record LearnTradingCandidate(
        String title,
        String learnCardId,
        String sourceType,
        LocalDate created,
        String coreView,
        List<String> keyPoints,
        String tradeNote,
        List<String> tags) {

    public LearnTradingCandidate {
        if (title == null || title.isBlank()) {
            throw new LearnException("候选标题不能为空");
        }
        if (learnCardId == null || learnCardId.isBlank()) {
            throw new LearnException("候选缺少 learn 卡片回链");
        }
        if (created == null) {
            throw new LearnException("候选缺少生成日期");
        }
        coreView = coreView == null ? "" : coreView.strip();
        keyPoints = keyPoints == null ? List.of() : keyPoints;
        tradeNote = tradeNote == null ? "" : tradeNote.strip();
        tags = tags == null ? List.of() : tags;
    }
}
