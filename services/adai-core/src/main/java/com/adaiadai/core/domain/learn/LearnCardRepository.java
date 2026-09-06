package com.adaiadai.core.domain.learn;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * LearnCardRepository — 学习卡片存储端口（RFC 20260829）。
 * <p>
 * domain 端口（对齐 TradingCaseRepository：领域依赖倒置，实现归 infrastructure/storage）。
 * 实现：{@code data/{userId}/learn/{type}/{yyyy-MM-dd}_{title}.md}（File First，md 即卡片真相源）。
 */
public interface LearnCardRepository {

    /**
     * 保存卡片（写 md 文件；同日同 type 同 title 已存在 → 抛业务异常，防静默覆盖）。
     * 写失败抛 StorageException（fail-visible）。
     */
    void save(String userId, LearnCard card);

    /** 按 type + 标题精确读取；不存在 → 空。 */
    Optional<LearnCard> find(String userId, String type, String title);

    /** 指定类型的全部卡片（created 倒序；损坏文件跳过）。 */
    List<LearnCard> list(String userId, String type);

    /** 资产树：learn 目录下各 type 的卡片清单（只含本实现产出的规范卡片）。 */
    java.util.Map<String, List<LearnCard>> tree(String userId);

    /** 指定日期内是否存在同名标题（防同日重复消化覆盖）。 */
    boolean existsOn(String userId, String type, LocalDate created, String title);

    /** 落原始素材（LLM 失败时素材不丢）：learn/_raw/{yyyy-MM-dd}_{time}.txt。 */
    void saveRawSource(String userId, String content);
}
