package com.adaiadai.core.domain.learn;

import java.util.List;

/**
 * LearnDigestTaskRepository — 「整理任务」追踪记录的存储端口（2026-09-23 分享追踪批）。
 *
 * <p>实现：{@code data/{userId}/learn/_digest-tasks.json}（File First，与 {@code _quota.json} 并列，
 * 不落在 {@code learn/{type}/} 主题目录下 → 不会被卡片扫描当成交付物）。
 *
 * <p>为什么落盘而不是纯内存：任务态本身是内存的（跑完就没了），但**「我分享过什么、成了没有」是账**——
 * 重启、隔天、事后追问都还要答得出来（P1-分享7/8 的同一口径：失败/成功都不能从用户世界消失）。
 */
public interface LearnDigestTaskRepository {

    /**
     * 记录一条任务，或按 {@code id} 覆盖更新同一条（状态推进时反复调用）。
     *
     * <p>只保留最近若干条（滚动窗口，避免无限增长）；实现须在 per-user 锁内完成读-改-写
     * （pitfall「整文件重写并发」防复发）。
     *
     * <p><b>失败 fail-open（与 {@code LearnQuotaRepository} 的 fail-closed 刻意相反）</b>：
     * 配额是花钱闸门、读不出来必须停下；而本记录只是**账**——写不进去最多是「这条查不到踪迹」，
     * 但**绝不能反过来把用户这次整理判成失败**（卡片其实已经落盘了，谎报失败比没有追踪更糟）。
     * 所以实现只记 WARN，不抛异常。
     */
    void save(String userId, LearnDigestTask task);

    /** 最近的任务，新 → 旧；{@code limit <= 0} 时用实现默认值。读不出来按空列表降级（同上：追踪不该反过来阻断主流程）。 */
    List<LearnDigestTask> findRecent(String userId, int limit);

    /**
     * 空实现：什么都不记，也不抛。
     *
     * <p>给**测试装配**与「没接追踪」的场合用——让 {@code LearnDigestAppService} 的老构造器签名
     * 保持不变（既有 5 参构造点零改动），需要验证记录行为的测试再显式注入自己的实现。
     */
    LearnDigestTaskRepository NOOP = new LearnDigestTaskRepository() {
        @Override
        public void save(String userId, LearnDigestTask task) {
            // 刻意留空
        }

        @Override
        public List<LearnDigestTask> findRecent(String userId, int limit) {
            return List.of();
        }
    };
}
