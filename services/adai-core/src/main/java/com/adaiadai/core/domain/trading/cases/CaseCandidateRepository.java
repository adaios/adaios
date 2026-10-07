package com.adaiadai.core.domain.trading.cases;

import java.util.List;

/**
 * CaseCandidateRepository — 案例候选「你的决定」存储端口（2026-10-08，案例候选批）。
 *
 * <p>只存<b>有状态的</b>条目（ACCEPTED 已收下 / DISMISSED 不要——墓碑）：
 * 候选本身每次从数据现算、不落盘（文件 = 你的决定的真相源，File First；数据没变时文件不变）。
 * 实现：{@code data/{userId}/trading/case-candidates.json}。
 *
 * <p>domain 端口（P1-4 审查标准：领域依赖倒置，实现归 infrastructure/storage）。
 */
public interface CaseCandidateRepository {

    /** 该用户全部「有状态」条目（含墓碑；数据损坏 → 空列表，降级不坏）。 */
    List<CaseCandidate> findByUser(String userId);

    /** 保存一条「决定」（收下 / 不要；同 id 原位替换）。写失败抛 StorageException（fail-visible）。 */
    void upsert(String userId, CaseCandidate record);
}
