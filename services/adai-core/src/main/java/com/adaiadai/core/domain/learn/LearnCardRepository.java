package com.adaiadai.core.domain.learn;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * LearnCardRepository — 学习卡片存储端口（RFC 20260829）。
 * <p>
 * domain 端口（对齐 TradingCaseRepository：领域依赖倒置，实现归 infrastructure/storage）。
 * 实现：{@code data/{userId}/learn/{type}/{topic}/NN-{slug}.md}（File First，md 即卡片真相源；
 * 2026-09-12 结构统一批起按**主题目录**归档，与 Mac 侧技能产物同契约）。
 */
public interface LearnCardRepository {

    /**
     * 保存卡片（写 md 文件）。落 {@code learn/{type}/{topic}/NN-{slug}.md}（NN 主题内递增），
     * 并维护该主题的 README 索引。
     * <p>
     * **同 type + 同 title 的「本产品产出卡」已存在 → 抛 LearnException 400**（P1-learn2
     * 修复 2026-09-07：跨日同名是旧卡无法寻址/改错卡的根源）。别处整理的手工卡同名**不再拦截**
     * （P2-learn20 修复 2026-09-12：两者落在不同文件，寻址按「本产品卡优先」消解歧义）。
     * 写失败抛 StorageException（fail-visible）。
     */
    void save(String userId, LearnCard card);

    /**
     * 按 type + 标题精确读取。**本产品产出卡优先**；若只有别处整理的手工卡，则返回它
     * （writable=false，调用方按只读处理）。同为本产品产出且多张同名 → 抛 LearnException 400
     * （列出 created 日期，提示人工合并）——禁止静默取最新改错卡（P1-learn2）。
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

    /** 资产树：learn 目录下各 type 的卡片清单（含别处整理的手工卡，只读的 writable=false）。 */
    java.util.Map<String, List<LearnCard>> tree(String userId);

    /** 某 type 下已有主题目录名（供消化时提示 LLM 归并到同一主题；created 无关，纯目录清单）。 */
    List<String> topics(String userId, String type);

    /**
     * 读卡片**原始 md 全文**（2026-09-12 完整升级批）。
     * <p>
     * 为什么需要：解析后的 {@link LearnCard} 只带产品建模的四个段（核心观点/要点/疑问/复述），
     * 而 Mac 侧技能整理的卡还有「关键内容详解/金句/概念关系」等段——**列表看得见、全文读不全**。
     * 读全文按原文返回（md 即真相源），两种来源的卡都能完整呈现。
     *
     * @return md 全文；卡片不存在 → null
     */
    String readCard(String userId, String type, String title);

    /**
     * 卡片文件的**真实相对路径**（{@code learn/{type}/{topic}/NN-{slug}.md}）。
     * <p>
     * 用途：跨域回链（trading 候选的 {@code learn_card_id}）——2026-09-12 结构统一后，路径不能
     * 再按「日期_标题」拼（那样拼出来的是旧扁平格式的假路径，指向不存在的文件）。
     *
     * @return 用户层下的相对路径；卡片不存在 → null
     */
    String cardPath(String userId, String type, String title);

    /** 一次迁移动作（老 → 新）。 */
    record MigrationItem(String type, String from, String to) {}

    /**
     * 把**老式扁平卡**（V1/V2 产品格式 {@code {type}/{yyyy-MM-dd}_{title}.md}）一次性迁移到主题目录
     * （2026-09-12 完整升级批）。
     * <p>
     * 语义：主题取 frontmatter 里的 {@code topic}（缺省 →「未归类」），补写 {@code origin: product}
     * + {@code topic} 两个键，落到 {@code {type}/{topic}/NN-{slug}.md}（主题内续号）并维护该主题
     * README，然后删除老文件；**幂等**（迁完再跑返回空）；Mac 侧技能写的主题目录卡**一动不动**。
     * <p>
     * 安全：目标写成功但老文件删不掉时**回滚目标**（宁可不迁，也不留两张同名可写卡 → 寻址歧义）。
     *
     * @return 本次迁移明细（无可迁移 → 空列表）
     */
    List<MigrationItem> migrateLegacy(String userId);

    /**
     * 是否存在同名卡片（**兼容两种布局，按任意日期**）。
     * <p>
     * 注意：语义在 2026-09-12 结构统一批后为「同 type + 同 title 已存在」（{@code created} 参数
     * 只作历史签名保留，不再参与判定——save 层的重复闸早就是全日期口径）。
     */
    boolean existsOn(String userId, String type, LocalDate created, String title);

    /** 落原始素材（LLM 失败时素材不丢）：暂存区 {@code learn/_raw/{时间戳}.txt}（消化成功后随卡归位到主题目录）。 */
    void saveRawSource(String userId, String content);

    /**
     * 写**具名**原始素材（RFC 20260912 源必留痕铁律，learn 抓取批 2026-09-12）：
     * {@code learn/_raw/{name}}——**暂存区**（消化成功前主题未知）。
     * <p>
     * 与 {@link #saveRawSource} 的差异：具名 = **可寻址、可幂等**——同一素材重复处理时命中
     * 既有留痕（转写稿只烧一次钱、文章失效仍可从 {@code _raw/} 复原）。命名由抓取/转写侧按
     * 源标识生成（如 {@code bilibili-BVxxx-meta.json} / {@code article-{hash}-text.txt}），
     * 实现须清洗文件名防路径逃逸。
     */
    void saveRaw(String userId, String name, String content);

    /**
     * 读具名原始素材；不存在 → null。
     *
     * @see #saveRaw
     */
    String readRaw(String userId, String name);

    /**
     * 写**二进制**具名原始素材（图片源，2026-09-12 完整升级批）：{@code learn/_raw/{name}}。
     * <p>
     * 为什么单独一个方法：原图是二进制（截图/书页/PPT），且**丢了不可重建**（技能「源必留痕」
     * 铁律）——不能塞进 String 通道。与 {@link #saveRaw} 同样是暂存区，消化成功后
     * {@link #promoteRaw} 归位到主题目录。
     */
    void saveRawBytes(String userId, String name, byte[] bytes);

    /** 读二进制具名原始素材；不存在 → null。 */
    byte[] readRawBytes(String userId, String name);

    /**
     * 删除具名原始素材（暂存区与主题目录两处都试；不存在不报错）。
     * <p>
     * 用途：受理失败时清理刚落盘的孤儿素材（2026-09-12 对抗审查 P1-B 修复配套）。
     */
    void deleteRaw(String userId, String name);

    /**
     * 消化成功后的「素材归位」（2026-09-12 结构统一批）：把暂存区 {@code learn/_raw/{name}} 的
     * 具名素材**搬到** {@code learn/{type}/{topic}/_raw/{name}}——与 Mac 侧技能「`_raw/` 在主题目录内」
     * 同契约，源与卡放在一起才可复原。暂存区文件不存在（如未转写）→ 跳过；搬完删除暂存副本。
     * <p>
     * 失败不抛（留痕是附带收益，不该让已花钱的消化失败）——仅告警。
     *
     * @return 实际归位的素材名
     */
    List<String> promoteRaw(String userId, String type, String topic, List<String> names);
}
