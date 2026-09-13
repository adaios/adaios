package com.adaiadai.core.infrastructure.push;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.PushDeviceFileRepository;
import com.adaiadai.core.kernel.push.PushChannel;
import com.adaiadai.core.kernel.push.PushDevice;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ApnsLiveSmokeTest — **真实 APNs 投递冒烟**（默认跳过，需显式给凭据与真机 token）。
 * <p>
 * <b>为什么需要它</b>：本项目的推送渠道是 fail-safe 设计——凭据错、签名格式错、网关卡错，
 * {@code push()} 一律「一声不吭」（只 warn 日志），定时任务照常跑完。于是「渠道 enabled=true」
 * 与「通知真的到了手机」之间隔着一整条没人验的链路（这正是 2026-09-13 之前 10 种推送
 * 都「投产」却只能靠 Bark 转达的原因）。本测试把这条链路**手工可复现地**验一次：
 * 用真 `.p8` + 真 deviceToken 走一遍 {@link ApnsPushChannel}，断言 APNs 回的是 **200**。
 * <p>
 * <b>何时跑</b>：① 首次配置 APNs；② 换 key / 换 Team / 换 App（bundle id）之后；
 * ③ 付费账号年度续期或重建描述文件之后；④ 怀疑「怎么没通知」时先排除凭据面。
 * <p>
 * <b>怎么跑</b>（凭据只在本地进程环境里，不写进任何文件）：
 * <pre>
 * cd services/adai-core
 * ADAI_APNS_LIVE_KEY_PATH=~/Downloads/AuthKey_XXXXXXXXXX.p8 \
 * ADAI_APNS_LIVE_KEY_ID=XXXXXXXXXX \
 * ADAI_APNS_LIVE_TEAM_ID=4G3D37YKSB \
 * ADAI_APNS_LIVE_TOKEN=&lt;64 位十六进制真机 token&gt; \
 * ADAI_APNS_LIVE_ENV=sandbox \
 * ./gradlew test --tests "*ApnsLiveSmokeTest*"
 * </pre>
 * 未设 {@code ADAI_APNS_LIVE_KEY_PATH} / {@code ADAI_APNS_LIVE_TOKEN} → 整类跳过
 * （本地与 CI 的常规测试不受影响，也不会计入「必须绿」的基线）。
 * <p>
 * 真机 token 从哪来：{@code GET /api/v1/push/devices}（App 打开一次即登记）。
 * <b>注意</b>：跑这个测试会**真的往你手机上发一条通知**——这是设计意图，不是副作用。
 */
class ApnsLiveSmokeTest {

    private static final String BUNDLE_ID = "com.adaiadai.adaiApp";

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    @Test
    void liveDeliver_realDevice_apnsReturns200() throws Exception {
        String keyPath = env("ADAI_APNS_LIVE_KEY_PATH");
        String keyId = env("ADAI_APNS_LIVE_KEY_ID");
        String teamId = env("ADAI_APNS_LIVE_TEAM_ID");
        String token = env("ADAI_APNS_LIVE_TOKEN");
        String environment = PushDevice.normalizeEnvironment(env("ADAI_APNS_LIVE_ENV"));

        assumeTrue(keyPath != null && token != null && keyId != null && teamId != null,
                "未配 ADAI_APNS_LIVE_* → 跳过真实投递冒烟（仅手工触发，见类注释）");

        InMemoryFileStorage storage = new InMemoryFileStorage();
        PushDeviceFileRepository repo = new PushDeviceFileRepository(storage);
        String now = Instant.now().toString();
        PushDevice device = new PushDevice(token, PushDevice.PLATFORM_IOS, environment,
                BUNDLE_ID, "iPhone", now, now);
        repo.save("adai", device);

        // 走真实公网网关（不覆写 host）；types 留空 = 不受灰度白名单限制
        ApnsPushChannel channel = new ApnsPushChannel(repo, keyPath, keyId, teamId, BUNDLE_ID,
                true, "", ApnsPushChannel.SANDBOX_HOST, ApnsPushChannel.PRODUCTION_HOST);
        assertTrue(channel.enabled(), "凭据应能加载（.p8 可解析 + keyId/teamId 齐全）");

        ApnsPushChannel.DeliveryResult result = channel.deliverNow("adai",
                device, new PushChannel.PushMessage(
                        "阿呆·投递自检",
                        "看到这条说明推送链路通了（真实 APNs 投递冒烟）",
                        "close-summary", null, null, LocalTime.now()));

        assertEquals(200, result.status(),
                "APNs 未接受投递 | reason=" + result.reason()
                        + "（BadDeviceToken=环境/网关不匹配；InvalidProviderToken=.p8 与 key-id/team-id 不配套）");
        assertTrue(result.ok());
    }
}
