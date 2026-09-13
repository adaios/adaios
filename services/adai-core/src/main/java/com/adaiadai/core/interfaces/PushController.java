package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.PushDeviceAppService;
import com.adaiadai.core.kernel.push.PushDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PushController — 推送设备登记与链路自检（RFC 20260913 APNs 批）。
 * <p>
 * 端点：
 * <pre>
 * POST   /api/v1/push/devices          登记/刷新本机 APNs token（幂等 upsert）
 * GET    /api/v1/push/devices          本账号已登记的设备
 * DELETE /api/v1/push/devices/{token}  注销设备（登出或 APNs 回 410 时）
 * GET    /api/v1/push/status           推送链路自检（渠道就绪 + 灰度白名单 + 设备数）
 * </pre>
 * <p>
 * <b>无插件门控</b>：推送跨 feed/trading/learn 三域，按插件门控会把纯 learn 用户的通知挡掉
 * （先例：learn-review 开关归属 trading 门控的教训，S-learn2）。
 * X-User-Id 隔离（{@code data/{userId}/push/devices.json}）。
 * <p>
 * 与既有 {@code /trading/push-settings} 的分工：那个管「哪些**类型**要推」（用户偏好，按类型开关）；
 * 本控制器管「推到**哪台设备**」（通道与目标）。两者正交，互不覆盖。
 */
@RestController
@RequestMapping("/api/v1/push")
public class PushController {

    private static final Logger log = LoggerFactory.getLogger(PushController.class);

    private final PushDeviceAppService pushDeviceService;

    public PushController(PushDeviceAppService pushDeviceService) {
        this.pushDeviceService = pushDeviceService;
    }

    /** 登记设备：客户端拿到 APNs deviceToken 后上报；同 token 重复上报只刷新 lastSeenAt。 */
    @PostMapping("/devices")
    public ResponseEntity<?> register(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody PushDeviceRequest body) {
        if (body == null || body.token() == null || body.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少设备推送标识"));
        }
        if (!PushDevice.isValidToken(body.token())) {
            return ResponseEntity.badRequest().body(Map.of("error", "设备推送标识不合法（应为十六进制 token）"));
        }
        PushDevice device = pushDeviceService.register(userId, body.token(), body.platform(),
                body.environment(), body.bundleId(), body.label());
        log.info("推送设备登记请求 | userId={} | env={}", userId, device.environment());
        return ResponseEntity.ok(device);
    }

    /** 已登记设备（App 侧「本机是否已登记」自检用）。 */
    @GetMapping("/devices")
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return ResponseEntity.ok(pushDeviceService.devices(userId));
    }

    /** 注销设备（幂等）。 */
    @DeleteMapping("/devices/{token}")
    public ResponseEntity<?> unregister(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String token) {
        boolean removed = pushDeviceService.unregister(userId, token);
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    /**
     * 推送链路自检：apns 渠道 enabled/configured/灰度白名单 + 已登记设备数。
     * 配完 .p8 后用这一条确认生效，不必等下一次定时推送。
     */
    @GetMapping("/status")
    public ResponseEntity<?> status(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return ResponseEntity.ok(pushDeviceService.status(userId));
    }

    /** 设备上报体。 */
    public record PushDeviceRequest(
            String token,
            String platform,
            String environment,
            String bundleId,
            String label
    ) {}
}
