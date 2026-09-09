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
     * 保存卡片（写 md 文件）。**同 type + 同 title 已存在（任意日期）→ 抛 LearnException 400**
     * （P1-learn2 修复 2026-09-07：跨日同名是旧卡无法寻址/改错卡的根源，从喂入端拒绝重复同名，
     * 同标题内容请先查看/编辑）。写失败抛 StorageException（fail-visible）。
     */
    void save(String userId, LearnCard card);

    /**
     * 按 type + 标题精确读取。同 type + 同 title 若存在**多张**（历史数据/手工文件）→ 抛
     * LearnException 400（列出 created 日期，提示人工合并）——禁止静默取最新改错卡（P1-learn2）。
     * 不存在 → 空。
     */
    Optional<LearnCard> find(String userId, String type, String title);

    /** 指定类型的全部卡片（created 倒序；损坏文件跳过）。 */
    List<LearnCard> list(String userId, String type);

    /**
     * 复习状态流转（V2 new→review→done + 回退 review→new / done→review）。
     * 按 type + 标题定位并原地更新 frontmatter status（File First：md 即真相源，正文/复述段
     * 原样保留）。{@code today} 由调用方（application 层）传入，用于：
     * <ul>
     *   <li>进入 review（toStatus=review 且当前非 review，含 done→review 重进）→ 写
     *       {@code review_at=today} 并清 {@code reminded_at}（S-learn1：复习提醒按进入复习之日
     *       计时，不再按消化日 created 误计时）</li>
     *   <li>离开 review（toStatus∈new/done）→ 清 review_at/reminded_at（不再提醒）</li>
     * </ul>
     * 不存在 → LearnException；写失败 → StorageException。
     *
     * @return 更新后的卡片
     */
    LearnCard updateStatus(String userId, String type, String title, String toStatus, LocalDate today);

    /**
     * 标记复习提醒已推送（S-learn1 节流）：写 {@code reminded_at=today}（同卡 7 天内不重复提醒，
     * 防「搁置卡每晚 nag」）。不改变 status。不存在 → LearnException。
     */
    LearnCard markReminded(String userId, String type, String title, LocalDate today);

    /**
     * 编辑卡片正文（V2 编辑）。定位 = type + title（多张同名 → LearnException）。
     * 补丁字段 null = 保留原值。**在仓储锁内读-改-写原子完成**（P2-learn6：并发 PATCH 不丢
     * 更新）；写盘基于原文件做受管键/正文段手术替换，**手工未知 frontmatter 键与未知正文段
     * 原样保留**（P2-learn7：File First 不抹手工内容）。type/title/created 不可改。
     *
     * @return 更新后的卡片
     */
    LearnCard applyEdit(String userId, String type, String title, LearnCardPatch patch);

    /**
     * 覆盖更新卡片正文（V2 编辑全量语义，兼容旧调用/测试）。定位 = type + title；
     * 已存在才可更新，type/title/created 不得变更（路径守卫）。写盘同样保留未知段。
     *
     * @return 更新后的卡片
     */
    LearnCard update(String userId, LearnCard card);

    /** 资产树：learn 目录下各 type 的卡片清单（只含本实现产出的规范卡片）。 */
    java.util.Map<String, List<LearnCard>> tree(String userId);

    /** 指定日期内是否存在同名标题（防同日重复消化覆盖；save 层已升级全日期同名拒绝）。 */
    boolean existsOn(String userId, String type, LocalDate created, String title);

    /** 落原始素材（LLM 失败时素材不丢）：learn/_raw/{yyyy-MM-dd}_{time}.txt。 */
    void saveRawSource(String userId, String content);
}
