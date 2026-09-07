package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnTradingCandidate;
import com.adaiadai.core.domain.learn.LearnTradingCandidateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LearnCandidateAppServiceTest — learn → trading 反哺候选编排（RFC 20260829 V2 批 3）。
 * <p>
 * 验证：trading+trade_related 卡片 → 候选（learn_card_id 回链 + 提炼字段）；非 trading 类型拒绝；
 * 卡片不存在 400；trade_related=false 拒绝（用户审核闸前置）；列表/删除透传。
 */
class LearnCandidateAppServiceTest {

    private final LearnCardRepository cardRepository = mock(LearnCardRepository.class);
    private final LearnTradingCandidateRepository candidateRepository = mock(LearnTradingCandidateRepository.class);
    private LearnCandidateAppService service;

    @BeforeEach
    void setUp() {
        service = new LearnCandidateAppService(cardRepository, candidateRepository);
    }

    private LearnCard tradingCard(String title, boolean tradeRelated, String tradeNote) {
        return new LearnCard(LearnCard.TYPE_TRADING, title, "bilibili", "某UP",
                "https://b23.tv/x", "2026-05-05", LocalDate.of(2026, 9, 6),
                LearnCard.STATUS_NEW, tradeRelated, tradeNote,
                List.of("止损"), "回调到一半才是买点", List.of("02:31 回调一半=(high+low)/2"),
                List.of(), "");
    }

    @Test
    void createFromCard_tradingRelatedCard_generatesCandidateWithLearnCardId() {
        LearnCard card = tradingCard("回调一半的判定", true, "与 R66 止损互补");
        when(cardRepository.find(anyString(), anyString(), anyString())).thenReturn(Optional.of(card));

        LearnTradingCandidate c = service.createFromCard("adai", "trading", "回调一半的判定");

        assertEquals("回调一半的判定", c.title());
        assertTrue(c.learnCardId().startsWith("learn/trading/"), "回链指向 learn 卡片文件: " + c.learnCardId());
        assertTrue(c.learnCardId().contains(card.created().toString()), "回链含源卡片日期");
        assertEquals("trading", c.sourceType());
        assertEquals("回调到一半才是买点", c.coreView());
        assertEquals(1, c.keyPoints().size());
        assertEquals("与 R66 止损互补", c.tradeNote());
        assertEquals(List.of("止损"), c.tags());
        verify(candidateRepository).save(anyString(), any());
    }

    @Test
    void createFromCard_nonTradingType_throws() {
        LearnException ex = assertThrows(LearnException.class,
                () -> service.createFromCard("adai", "ai", "RAG 与 Agent"));
        assertTrue(ex.getMessage().contains("trading"));
        verify(candidateRepository, never()).save(anyString(), any());
    }

    @Test
    void createFromCard_cardNotFound_throws() {
        when(cardRepository.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        LearnException ex = assertThrows(LearnException.class,
                () -> service.createFromCard("adai", "trading", "不存在的卡"));
        assertTrue(ex.getMessage().contains("卡片不存在"));
    }

    @Test
    void createFromCard_notTradeRelated_throwsUserReviewGate() {
        // 防语义漂移：未标注 trade_related 的卡片不许直接反哺（先让阿呆补标/确认）
        LearnCard card = tradingCard("心态类内容", false, "");
        when(cardRepository.find(anyString(), anyString(), anyString())).thenReturn(Optional.of(card));
        LearnException ex = assertThrows(LearnException.class,
                () -> service.createFromCard("adai", "trading", "心态类内容"));
        assertTrue(ex.getMessage().contains("trade_related"), "提示先确认: " + ex.getMessage());
        verify(candidateRepository, never()).save(anyString(), any());
    }

    @Test
    void createFromCard_blankTitle_throws() {
        LearnException ex = assertThrows(LearnException.class,
                () -> service.createFromCard("adai", "trading", "  "));
        assertTrue(ex.getMessage().contains("标题"));
    }

    @Test
    void listCandidates_delegates() {
        LearnTradingCandidate c = new LearnTradingCandidate("回调一半的判定",
                "learn/trading/2026-09-06_回调一半的判定", "trading", LocalDate.of(2026, 9, 7),
                "观点", List.of(), "备注", List.of());
        when(candidateRepository.list("adai")).thenReturn(List.of(c));
        assertEquals(1, service.listCandidates("adai").size());
    }

    @Test
    void deleteCandidate_delegates() {
        service.deleteCandidate("adai", "回调一半的判定");
        verify(candidateRepository).delete("adai", "回调一半的判定");
    }
}
