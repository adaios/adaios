package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ApiTokenGuard — 外部令牌的**付费动作闸门**（REVIEW S-凭据1 剩余项，2026-09-17 B4 批）。
 *
 * <p>背景：外部令牌（Siri / 快捷指令 / 分享扩展用的那把 {@code learn:digest} 限权钥匙）此前只有
 * 「有效期 90 天 + 可撤销 + 轮换」几道闸，而 {@code POST /learn/digest/confirm} 这个**付费**动作
 * 对令牌**没有任何频控**——一把被转发出去的钥匙最坏能连点刷转写（花的是号主的钱）。
 *
 * <p>本类补三道：
 * <ol>
 *   <li><b>频控</b>：同一把令牌每分钟最多 {@value #PAY_ACTION_PER_MINUTE} 次付费动作（内存滑动窗口）。
 *       单实例部署下的硬约束（learn 本就是单实例硬要求，见部署文档 §6.1）；重启即重置——频控是
 *       防滥用，不是账务，这个精度足够。</li>
 *   <li><b>月上限</b>：**沿用 learn 既有的月度转写额度**（{@code adai.learn.asr.month-quota-seconds}，
 *       默认 30 小时）——不新造第二个数字、不引入第二个口径；额度耗尽由转写服务照旧拒绝。</li>
 *   <li><b>异常使用告警</b>：同一把令牌换了来源 IP、或触发频控 → WARN（含账号 + 令牌前缀 + IP），
 *       由每日巡检的日报捞出来给人看。</li>
 * </ol>
 *
 * <p><b>会话调用不受限</b>：用户自己在 App / Web 里点「继续转写」不带 tokenId，直接放行——
 * 这道闸限的是「这把钥匙被谁拿到」，不是限号主本人。
 */
@Component
public class ApiTokenGuard {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenGuard.class);

    /** 同一把令牌每分钟允许的付费动作次数（2026-09-17 用户拍板 D5：5 次/分钟）。 */
    static final int PAY_ACTION_PER_MINUTE = 5;
    private static final long WINDOW_MS = 60_000L;

    /** 令牌 → 最近一分钟内的动作时间戳（滑动窗口）。 */
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    /** 令牌 → 上次出现的来源 IP（用于「同一把钥匙换 IP」告警）。 */
    private final Map<String, String> lastIp = new ConcurrentHashMap<>();

    /**
     * 付费动作前的闸门（目前挂在 {@code POST /learn/digest/confirm} 上）。
     *
     * @param userId  令牌所属账号（仅用于告警文案）
     * @param tokenId 外部令牌标识（由 AuthFilter 注入，客户端**无法伪造**；会话调用为 null）
     * @param ip      调用方 IP（可为空）
     * @throws LearnException 超过令牌级频控（人话提示；调用方无需 catch，交全局异常处理）
     */
    public void checkPayAction(String userId, String tokenId, String ip) {
        if (tokenId == null || tokenId.isBlank()) {
            return; // 会话调用：用户自己在 App 里点，不限
        }
        warnIfIpChanged(userId, tokenId, ip);

        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(tokenId, k -> new ArrayDeque<>());
        int hits;
        synchronized (window) {
            // 顺手清理过期条目：窗口最多 5 条，不会随调用量增长（也不会漏掉空闲令牌的回收）
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }
            window.addLast(now);
            hits = window.size();
        }
        if (hits > PAY_ACTION_PER_MINUTE) {
            log.warn("外部令牌异常使用（付费动作超频）| userId={} | token={} | ip={} | 一分钟内第 {} 次（上限 {}）",
                    userId, tokenId, ip, hits, PAY_ACTION_PER_MINUTE);
            throw new LearnException("这把钥匙一分钟内已经用了 " + PAY_ACTION_PER_MINUTE
                    + " 次（整理是花钱动作，我先拦一下）；过一分钟再试，或者到「学习」页看看是不是该收回它");
        }
    }

    /**
     * 同一把令牌换了来源 IP → 告警（不阻断）。
     * <p>
     * 为什么不阻断：手机切 Wi-Fi/蜂窝、运营商 NAT 出口变化都会换 IP，硬拦会误伤正常使用。
     * 但「一把快捷指令钥匙突然从另一个地方被用」正是令牌外泄的典型信号，所以**必须留下痕迹**
     * ——WARN 会进生产日志，每日巡检的日报会把它捞出来。
     */
    private void warnIfIpChanged(String userId, String tokenId, String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        String previous = lastIp.put(tokenId, ip);
        if (previous != null && !previous.equals(ip)) {
            log.warn("外部令牌异常使用（同一把钥匙换了来源 IP）| userId={} | token={} | {} → {}"
                            + "——若本人没有换网络，建议直接撤销这把令牌",
                    userId, tokenId, previous, ip);
        }
    }

    /** 测试/运维用：清空窗口与 IP 记忆（不影响任何持久状态）。 */
    void reset() {
        windows.clear();
        lastIp.clear();
    }
}
