package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnTradingCandidate;
import com.adaiadai.core.domain.learn.LearnTradingCandidateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * LearnCandidateAppService — learn → trading 反哺候选用例编排（RFC 20260829 3.5③，V2 批 3）。
 * <p>
 * type=trading 且 trade_related=true 的 learn 卡片 → 生成一条「建议卡」候选落
 * {@code data/{userId}/trading/candidates/}（只存提炼建议 + learn_card_id 回链，跨域无双写）。
 * 候选不自动入库：用户在交易知识库工作流审核后融合归正式目录（对齐「规则改动须人工闸」）。
 * <p>
 * 候选归属 learn 域动作（从 learn 卡片发起），落盘位置按 RFC 3.3 约定在 trading 数据区
 * candidates/ 目录——learn 只写建议卡、不读 trading 业务数据（B6 跨域引用不搬移）。
 */
@Service
public class LearnCandidateAppService {

    private static final Logger log = LoggerFactory.getLogger(LearnCandidateAppService.class);

    private final LearnCardRepository cardRepository;
    private final LearnTradingCandidateRepository candidateRepository;

    public LearnCandidateAppService(LearnCardRepository cardRepository,
                                    LearnTradingCandidateRepository candidateRepository) {
        this.cardRepository = cardRepository;
        this.candidateRepository = candidateRepository;
    }

    /**
     * 从一张 learn 卡片生成 trading 反哺候选。
     *
     * @param userId 用户
     * @param type   卡片类型（必须 trading）
     * @param title  卡片标题（精确匹配）
     * @return 落盘的候选
     * @throws LearnException 类型非 trading / 卡片不存在 / 卡片未标 trade_related（400 人话）
     */
    public LearnTradingCandidate createFromCard(String userId, String type, String title) {
        if (!LearnCard.TYPE_TRADING.equals(type)) {
            throw new LearnException("只有交易类（trading）卡片能反哺成规则候选");
        }
        if (title == null || title.isBlank()) {
            throw new LearnException("卡片标题不能为空");
        }
        LearnCard card = cardRepository.find(userId, type, title)
                .orElseThrow(() -> new LearnException("卡片不存在：" + type + "/" + title));
        if (!card.tradeRelated()) {
            throw new LearnException("该卡片未标注涉及可执行交易规则（trade_related=false），先让阿呆补标或确认内容后再反哺");
        }
        // learn_card_id 回链 = learn 卡片文件**真实相对路径**（2026-09-12 结构统一批：路径按主题
        // 目录定位，不能再按「日期_标题」拼——那样拼的是旧扁平格式的假路径，指向不存在的文件；
        // P1-learn1 的原修复是拼路径，结构一变就失效，改为直接问仓储要真实路径）
        String learnCardId = cardRepository.cardPath(userId, type, title);
        if (learnCardId == null) {
            throw new LearnException("卡片不存在：" + type + "/" + title);
        }
        LearnTradingCandidate candidate = new LearnTradingCandidate(
                card.title(),
                learnCardId,
                card.type(),
                LocalDate.now(),
                card.coreView(),
                card.keyPoints(),
                card.tradeNote(),
                card.tags());
        candidateRepository.save(userId, candidate);
        log.info("learn → trading 候选生成 | userId={} | title={} | learnCard={}",
                userId, candidate.title(), learnCardId);
        return candidate;
    }

    /** 候选列表（created 倒序；用户审核用）。 */
    public List<LearnTradingCandidate> listCandidates(String userId) {
        return candidateRepository.list(userId);
    }

    /** 删除候选（按标题；幂等）。 */
    public void deleteCandidate(String userId, String title) {
        if (title == null || title.isBlank()) return;
        candidateRepository.delete(userId, title);
        log.info("learn → trading 候选删除 | userId={} | title={}", userId, title);
    }
}
