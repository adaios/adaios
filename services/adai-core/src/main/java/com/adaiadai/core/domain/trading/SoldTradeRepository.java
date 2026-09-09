package com.adaiadai.core.domain.trading;

import java.util.List;

/**
 * SoldTradeRepository — 清仓股存储端口（File First：data/{userId}/trading/sold.json）。
 */
public interface SoldTradeRepository {

    /** 读取全部清仓股；无文件/损坏返回空列表。 */
    List<SoldTrade> findAll(String userId);

    /** 全量保存清仓股。 */
    void saveAll(String userId, List<SoldTrade> trades);

    /**
     * 流水自动收录专用原子 upsert（RFC 20260909 批 1，ClearanceDetector 唯一写入口）：
     * 锁内「findAll → 若无同 symbol 行则追加 → saveAll」。
     * <p>
     * 用户拍板 P1：flow 自动收录<b>只填空白 symbol</b>——sold.json 已存在该 symbol
     * （无论 provenance=import/flow/人工行）绝不覆盖，仅补缺失。并发丢更新由仓储
     * 内部条带锁防（与 soldImport/saveAll 同一把锁，读-改-写原子）。
     */
    void upsertFromFlow(String userId, SoldTrade trade);
}
