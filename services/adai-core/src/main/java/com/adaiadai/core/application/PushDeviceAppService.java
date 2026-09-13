package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.PushDeviceFileRepository;
import com.adaiadai.core.kernel.push.PushChannel;
import com.adaiadai.core.kernel.push.PushDevice;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PushDeviceAppService — 推送设备登记 + 渠道状态（RFC 20260913 APNs 批）。
 * <p>
 * 边界：本服务只管「送给谁」（设备登记）与「有哪些送法可用」（渠道状态）；
 * 「什么时候推什么内容」仍归各推送生产方（TradingSessionPushService / MarketAlertService /
 * LearnReviewPushService），本批不碰它们（渠道插件化扇出，见 {@link PushChannel}）。
 * <p>
 * 不做插件门控：推送是跨域的（feed + trading + learn 都有推送类型），
 * 按插件门控会把纯 learn 用户或纯 trading 用户的通知挡掉（先例：learn-review 开关归属教训）。
 */
@Service
public class PushDeviceAppService {

    private final PushDeviceFileRepository deviceRepository;
    private final List<PushChannel> pushChannels;

    public PushDeviceAppService(PushDeviceFileRepository deviceRepository,
                                List<PushChannel> pushChannels) {
        this.deviceRepository = deviceRepository;
        this.pushChannels = pushChannels;
    }

    /**
     * 登记（或刷新）一台设备。
     *
     * @throws IllegalArgumentException token 非法（Controller 翻成 400 人话）
     */
    public PushDevice register(String userId, String token, String platform, String environment,
                               String bundleId, String label) {
        if (!PushDevice.isValidToken(token)) {
            throw new IllegalArgumentException("设备推送标识不合法（应为十六进制 token）");
        }
        String now = Instant.now().toString();
        PushDevice device = new PushDevice(
                token.trim(),
                platform == null || platform.isBlank() ? PushDevice.PLATFORM_IOS : platform.trim(),
                PushDevice.normalizeEnvironment(environment),
                bundleId == null || bundleId.isBlank() ? null : bundleId.trim(),
                label == null || label.isBlank() ? null : label.trim(),
                now,
                now);
        return deviceRepository.save(userId, device);
    }

    /** 注销设备（幂等：不存在返回 false，不报错）。 */
    public boolean unregister(String userId, String token) {
        if (token == null || token.isBlank()) return false;
        return deviceRepository.remove(userId, token.trim());
    }

    /** 该用户已登记的设备。 */
    public List<PushDevice> devices(String userId) {
        return deviceRepository.findByUser(userId);
    }

    /**
     * 推送链路自检：渠道是否就绪 + 已登记设备数。
     * <p>
     * 存在的意义是「配完 .p8 之后怎么知道生效了」——不必等下一次定时推送，
     * 一条 GET 就能看到 apns 渠道 enabled=true/false 以及白名单，排查成本从「猜」降到「看」。
     */
    public Map<String, Object> status(String userId) {
        List<Map<String, Object>> channels = new ArrayList<>();
        for (PushChannel channel : pushChannels) {
            channels.add(channel.status());
        }
        List<PushDevice> devices = deviceRepository.findByUser(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channels", channels);
        out.put("deviceCount", devices.size());
        out.put("devices", devices);
        return out;
    }
}
