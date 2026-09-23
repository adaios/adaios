package com.adaiadai.core.domain.learn;

/**
 * LearnDigestTask — 一次「把内容交给阿呆整理」的追踪记录（2026-09-23 分享追踪批）。
 *
 * <p><b>为什么需要它</b>：此前任务态只有 {@code LearnDigestAppService} 内存里的**单槽**
 * （{@code jobs} map，key=userId，且结果 60 秒/30 分钟后惰性清理）——它能回答「现在这一个跑到哪了」，
 * 却**答不了「我今天分享过哪些、分别什么情况」**。2026-09-23 用户原话：「我还没办法追踪呀……我看不到
 * 任何追踪信息，比如我分享了什么到阿呆，目前分别是什么情况了，是否完成了呢」。
 * （当天他 23:06:05 分享的公众号文章 19 秒就跑完了，而他 23:06:19 / 23:06:39 两次打开学习页
 * 什么也没看到——后端有日志、前端零呈现。）
 *
 * <p><b>与内存 job 的分工</b>：内存 job 是**执行态**（谁在跑、阶段、等确认），本记录是**账**
 * （谁提交过什么、结果如何）——所以它落盘（重启不丢、失败可事后查，延续 P1-分享7/8 那条
 * 「失败不能从用户世界消失」的口径）。
 *
 * <p><b>刻意不做的</b>：不做排队（2026-09-23 用户拍板「暂时不用，单任务够」）——同账号同时只有
 * 一个任务在跑这条约束不变，第二条仍会被拒（扩展会如实说「没排上」）；本记录只负责**让每一条
 * 提交与它的结局都留痕**。
 *
 * @param id          任务 id（含毫秒，同秒多次提交不互相覆盖）
 * @param url         提交的来源链接（可能为空的仅粘贴素材场景为空串）
 * @param sourceTitle 抓取到的原文标题（抓取成功前为 null）
 * @param platform    来源平台展示值（weibo/wechat/bilibili/文章域名…，抓取后回填）
 * @param status      running / needs_confirmation / done / failed / cancelled
 * @param stage       进行中阶段 fetching / reading / transcribing / structuring（终态为 null）
 * @param message     失败原因或等确认报价（人话，可直接展示）
 * @param type        结果卡片 type（done 时）
 * @param title       结果卡片标题（done 时——「哪一条」必须指名道姓）
 * @param topic       结果卡片主题（done 时，供前端分组或无痛打开）
 * @param submittedAt 提交时刻（ISO local date-time）
 * @param settledAt   进入终态的时刻（进行中为 null）
 */
public record LearnDigestTask(
        String id,
        String url,
        String sourceTitle,
        String platform,
        String status,
        String stage,
        String message,
        String type,
        String title,
        String topic,
        String submittedAt,
        String settledAt) {

    /** 是否还在进行（含等用户拍板）——前端据此决定要不要显示「正在读…」。 */
    public boolean inProgress() {
        return "running".equals(status) || "needs_confirmation".equals(status);
    }

    /** 是否已有结局（成功或失败或取消）——由「账」决定，不受内存 job 过期影响。 */
    public boolean settled() {
        return "done".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    /**
     * 提交那一刻的记录（还不知道抓不抓得到、更不知道会产出什么）。
     *
     * @param id          发号器给出的任务 id
     * @param url         来源链接（可空）
     * @param submittedAt 提交时刻（ISO local date-time，由 application 层取时钟——storage 层不取 now）
     */
    public static LearnDigestTask submitted(String id, String url, String submittedAt) {
        return new LearnDigestTask(id, url == null ? "" : url, null, null,
                "running", "fetching", null, null, null, null, submittedAt, null);
    }
}
