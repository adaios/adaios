package com.adaiadai.core.domain.trading.cases;

import java.util.List;
import java.util.Optional;

/**
 * TradingCaseRepository — 完美买点案例存储端口（2026-08-30：案例库环 2）。
 * <p>
 * domain 端口（P1-4 审查标准：领域依赖倒置，实现归 infrastructure/storage）。
 * 实现：{@code data/{userId}/trading/cases/}（File First，单案例一文件 + _index.json 清单）。
 */
public interface TradingCaseRepository {

    /** 按案例 id 读取；不存在/损坏 → 空。 */
    Optional<CaseRecord> findById(String userId, String caseId);

    /** 全部案例（按 buyDate 倒序，来自清单；清单缺失条目以文件为准重建）。 */
    List<CaseRecord> list(String userId);

    /** 保存案例（写案例文件 + upsert 清单；写失败抛 StorageException，fail-visible）。 */
    void save(String userId, CaseRecord record);

    /** 删除案例（移除清单条目 + 删案例文件）；不存在 → 空操作。 */
    void delete(String userId, String caseId);

    /** 是否已存在（同 symbol+buyDate 幂等键）。 */
    boolean exists(String userId, String caseId);
}
