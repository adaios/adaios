package com.adaiadai.core.domain.learn;

import java.util.List;
import java.util.Optional;

/**
 * LearnTradingCandidateRepository — learn → trading 反哺候选存储端口（RFC 20260829，V2 批 3）。
 * <p>
 * 候选落 {@code data/{userId}/trading/candidates/{yyyy-MM-dd}_{title}.md}
 * （RFC 3.3 数据模型：V2 加 candidates/ 候选目录——learn 域动作写该目录，只写建议卡
 * 不读 trading 业务数据；跨域无双写，3.6 引用不搬移）。
 * <p>
 * 并发：per-user 条带锁（对齐 LearnCardFileRepository/交易规则层 P2-交易28 锁池模式）。
 * 写失败抛 StorageException（fail-visible）；防覆盖：同日同名候选已存在 → LearnException。
 */
public interface LearnTradingCandidateRepository {

    /**
     * 保存一条候选（写 md 建议卡；同日同名已存在 → LearnException 防覆盖）。
     *
     * @throws LearnException 同日同名候选已存在
     */
    void save(String userId, LearnTradingCandidate candidate);

    /** 按标题读取候选；不存在 → 空。 */
    Optional<LearnTradingCandidate> find(String userId, String title);

    /** 全部候选（created 倒序；损坏/异型文件跳过）。 */
    List<LearnTradingCandidate> list(String userId);

    /** 删除候选（按标题）；不存在 → 空操作（幂等）。 */
    void delete(String userId, String title);
}
