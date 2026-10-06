package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.kernel.push.PushChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * TradingPushGovernor — 推送日上限守卫（design-final §9#16 · ★V1 已拍板：全局 ≤8/日、超限合并）。
 * <p>
 * 交易线的推送有两条来源（本类不关心来源、只守总量）：
 * <ul>
 *   <li>{@link MarketAlertService#poll(String)} 的行情异动（批量：一轮可出多条）</li>
 *   <li>{@link TradingSessionPushService} 的定时推送（单条：早/午/尾盘、复盘、计划提醒…）</li>
 * </ul>
 * <b>准入规则</b>（计数源 = 当日已落盘推送 {@code data/{userId}/trading/pushes/{date}.json} 的条数，
 * 即用户当天已经收到几条）：
 * <ol>
 *   <li>{@code used >= 8} → 本批**不推**（空列表；调用方据此决定是否记签名，见各挂点注释）</li>
 *   <li>{@code used + batch <= 8} → 原样放行</li>
 *   <li>否则 → **整批折叠为一条汇总**（{@link #MERGED_TYPE}）——「多触发合成一条『今日 N 个提醒』」，
 *       <b>不丢弃</b>（V1 拍板原文）</li>
 * </ol>
 * <b>为什么折叠整批而不是截前 8−used 条</b>：截断会静默丢内容（违反「不丢弃」）；折叠后内容全在、
 * 只损失单条形态。单条调用方（定时推送）{@code used < 8} 时恒走规则 2——**单条永不折叠**，
 * 要么放行要么当日超限不推，语义直白。
 * <p>
 * <b>计数以 Feed 落盘为准</b>：所有 enabled 渠道在同一轮 push 中同步收到同一条消息，
 * FeedPushChannel 每次落盘 1 条 → 条数即「用户收到的消息数」；用户左滑删掉一条后计数随之减少
 * （主动清掉的空出额度），这是刻意的宽松——上限是防打扰的软闸，不是安全边界。
 * <p>
 * <b>已知窗口（如实记录，不修）</b>：admit 的读计数与渠道落盘之间未加锁——同一用户的异动轮询与
 * 定时推送**恰好在同一瞬间**并发时，两批可能各自按「旧计数」通过，总量到 9。生产为单用户、
 * 两条来源的时刻基本错开（异动 10:00-15:00 每 30 分钟 vs 定时 09:15/11:35/14:50/15:05/15:15/15:30），
 * 概率极低且超一条无害；为此加跨服务锁不值得。
 */
@Component
public class TradingPushGovernor {

    /** 全局日上限（★V1 拍板：全局 ≤8/日）。 */
    static final int DAILY_LIMIT = 8;

    /**
     * 折叠条的类型。**刻意不在** {@link MarketPushRepository#SESSION_TYPES}——
     * 它是汇总类，按「次日 23:59 过期」处理（行情类次日 09:30 消失，汇总条活到更晚才合理）。
     */
    public static final String MERGED_TYPE = "merged";

    private static final Logger log = LoggerFactory.getLogger(TradingPushGovernor.class);

    private final MarketPushRepository pushRepository;

    public TradingPushGovernor(MarketPushRepository pushRepository) {
        this.pushRepository = pushRepository;
    }

    /**
     * 批次准入：返回**允许实际推送**的消息列表（空列表 = 本批全不推）。
     * 返回的消息可能与入参同一批（原样放行），也可能是**一条折叠汇总**——调用方只需照常推送返回值。
     */
    public List<PushChannel.PushMessage> admit(String userId, List<PushChannel.PushMessage> batch) {
        if (batch == null || batch.isEmpty()) return List.of();
        int used = pushRepository.findByDate(userId, LocalDate.now()).size();
        if (used >= DAILY_LIMIT) {
            log.info("推送日上限已到（{}/{}），本批 {} 条不推 | userId={}",
                    used, DAILY_LIMIT, batch.size(), userId);
            return List.of();
        }
        if (used + batch.size() <= DAILY_LIMIT) {
            return batch;
        }
        log.info("推送将超日上限（{}+{}>{}），{} 条折叠为一条汇总 | userId={}",
                used, batch.size(), DAILY_LIMIT, batch.size(), userId);
        return List.of(merged(batch));
    }

    /** 折叠汇总之形：标题/锁屏标题中性（不带标的），正文保留各条原文（**不丢弃**）。 */
    private static PushChannel.PushMessage merged(List<PushChannel.PushMessage> batch) {
        String content = "今天还有 " + batch.size() + " 条提醒：\n"
                + batch.stream().map(PushChannel.PushMessage::content).collect(Collectors.joining("\n"));
        return new PushChannel.PushMessage(
                "今日提醒汇总", content, MERGED_TYPE, null, null, LocalTime.now(),
                "今天还有 " + batch.size() + " 条提醒。打开阿呆看看。", "今日提醒汇总");
    }
}
