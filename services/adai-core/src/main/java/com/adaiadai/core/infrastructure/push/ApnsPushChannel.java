package com.adaiadai.core.infrastructure.push;

import com.adaiadai.core.infrastructure.storage.PushDeviceFileRepository;
import com.adaiadai.core.kernel.push.PushChannel;
import com.adaiadai.core.kernel.push.PushDevice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ApnsPushChannel — 阿呆 app 自己的 iOS 推送渠道（RFC 20260913 APNs 批）。
 * <p>
 * <b>为什么值得做</b>：付费开发者账号之前，App 拿不到 {@code aps-environment} 能力，
 * 10 种推送（盘前/买点/止损/行情异动/收盘小结/复习提醒…）虽然全部投产，但「真正弹到手机」
 * 这一步只能借第三方 App（Bark）转达——通知上写着别人的名字，点击也回不到阿呆。
 * 本渠道让阿呆直连 APNs：{@link #name()} = {@code apns}，与 Bark 并存（渠道插件化扇出）。
 * <p>
 * <b>与推送生产方零耦合</b>：{@code TradingSessionPushService} / {@code MarketAlertService} /
 * {@code LearnReviewPushService} 注入的是 {@code List<PushChannel>}，本类挂上即生效，
 * 生产方一行不动（见 {@link PushChannel} 注释）。
 * <p>
 * <b>凭据</b>：APNs Auth Key（.p8，ES256）——比推送证书好，<b>不随年过期</b>，
 * 一把 key 覆盖该 Team 下所有 App、且 sandbox / production 两套网关通用
 * （这一点很关键：侧载是 sandbox token，将来上 TestFlight 变 production token，凭据不用换）。
 * <p>
 * 配置（{@code application.yml} → {@code adai.push.apns.*}）：
 * <pre>
 * adai.push.apns.enabled     是否启用（默认 true；缺 key 时 enabled() 仍为 false）
 * adai.push.apns.key-path    .p8 私钥路径（服务器本地，不进 git）
 * adai.push.apns.key-id      Apple 控制台生成的 Key ID（10 位）
 * adai.push.apns.team-id     Team ID（AdaiOS = 4G3D37YKSB）
 * adai.push.apns.bundle-id   App bundle id（apns-topic，默认 com.adaiadai.adaiApp）
 * adai.push.apns.types       只推这些推送类型（逗号分隔；留空 = 全部）——灰度放量用
 * </pre>
 * <p>
 * <b>灰度说明</b>：{@code types} 是给「第一刀只切收盘小结 + 复习提醒验证链路」准备的闸门，
 * 验证通过后清空即全量，无需改代码。
 * <p>
 * <b>失败一律 fail-visible 且不抛</b>：推送是尽力而为的动作，绝不能让外部网关的抖动
 * 把推送生产方的定时任务打挂（同 BarkPushChannel 的取舍）。
 */
@Component
public class ApnsPushChannel implements PushChannel {

    private static final Logger log = LoggerFactory.getLogger(ApnsPushChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SANDBOX_HOST = "https://api.sandbox.push.apple.com";
    static final String PRODUCTION_HOST = "https://api.push.apple.com";

    /** JWT 有效期（Apple 要求 20~60 分钟之间刷新一次，取 40 分钟留足余量）。 */
    private static final long JWT_TTL_SECONDS = 40 * 60;

    /** 通知保留期：手机离线时 APNs 代为存储 1 小时（0 = 不存储，会丢推送）。 */
    private static final long EXPIRATION_SECONDS = 60 * 60;

    private final PushDeviceFileRepository deviceRepository;
    private final String keyPath;
    private final String keyId;
    private final String teamId;
    private final String bundleId;
    private final String sandboxHost;
    private final String productionHost;
    private final boolean enabledFlag;
    private final List<String> typeAllowlist;

    private final HttpClient httpClient;

    /** .p8 解析结果；解析失败为 null（enabled() 随即为 false，构造时已 error 日志）。 */
    private final PrivateKey privateKey;

    private final Object jwtLock = new Object();
    private volatile String cachedJwt;
    private volatile long cachedJwtAt;

    public ApnsPushChannel(
            PushDeviceFileRepository deviceRepository,
            @Value("${adai.push.apns.key-path:}") String keyPath,
            @Value("${adai.push.apns.key-id:}") String keyId,
            @Value("${adai.push.apns.team-id:}") String teamId,
            @Value("${adai.push.apns.bundle-id:com.adaiadai.adaiApp}") String bundleId,
            @Value("${adai.push.apns.enabled:true}") boolean enabledFlag,
            @Value("${adai.push.apns.types:}") String types,
            @Value("${adai.push.apns.sandbox-host:" + SANDBOX_HOST + "}") String sandboxHost,
            @Value("${adai.push.apns.production-host:" + PRODUCTION_HOST + "}") String productionHost) {
        this.deviceRepository = deviceRepository;
        this.keyPath = keyPath == null ? "" : keyPath.trim();
        this.keyId = keyId == null ? "" : keyId.trim();
        this.teamId = teamId == null ? "" : teamId.trim();
        this.bundleId = bundleId == null ? "" : bundleId.trim();
        this.enabledFlag = enabledFlag;
        this.sandboxHost = blankTo(sandboxHost, SANDBOX_HOST);
        this.productionHost = blankTo(productionHost, PRODUCTION_HOST);
        this.typeAllowlist = parseTypes(types);
        // host 可指向本地 http 服务（单测用）：此时退 HTTP/1.1，否则 APNs 要求的 HTTP/2
        boolean h2 = this.sandboxHost.startsWith("https://") && this.productionHost.startsWith("https://");
        this.httpClient = HttpClient.newBuilder()
                .version(h2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.privateKey = loadPrivateKey();
        if (this.privateKey == null && enabledFlag && !this.keyPath.isBlank()) {
            log.error("APNs 渠道不可用：.p8 私钥加载失败 | key-path={}", this.keyPath);
        }
    }

    @Override
    public String name() {
        return "apns";
    }

    @Override
    public boolean enabled() {
        return enabledFlag && privateKey != null
                && !keyId.isBlank() && !teamId.isBlank() && !bundleId.isBlank();
    }

    /** 渠道状态摘要（{@code GET /push/status} 用）：不暴露任何凭据内容。 */
    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name());
        m.put("enabled", enabled());
        m.put("configured", privateKey != null);
        m.put("keyId", keyId.isBlank() ? null : keyId);
        m.put("teamId", teamId.isBlank() ? null : teamId);
        m.put("bundleId", bundleId);
        m.put("typeAllowlist", typeAllowlist.isEmpty() ? "全部" : String.join(",", typeAllowlist));
        return m;
    }

    @Override
    public void push(String userId, PushMessage message) {
        if (!enabled()) return;
        if (!typeAllowed(message.type())) {
            log.info("APNs 跳过（不在灰度白名单）| type={} | 白名单={}", message.type(), typeAllowlist);
            return;
        }
        List<PushDevice> devices = deviceRepository.findByUser(userId);
        if (devices.isEmpty()) {
            log.info("APNs 跳过（该用户未登记设备）| userId={} | type={}", userId, message.type());
            return;
        }
        String jwt;
        try {
            jwt = bearerToken();
        } catch (Exception e) {
            log.error("APNs 渠道不可用：JWT 签发失败 | {}", e.getMessage());
            return;
        }
        for (PushDevice device : devices) {
            deliver(userId, device, message, jwt);
        }
    }

    /**
     * 投递结果：APNs 的响应状态与 reason。
     * <p>
     * 之所以**返回**而不只打日志：真实投递冒烟（{@code ApnsLiveSmokeTest}）要能断言
     * 「APNs 真的回了 200」。对 fail-safe 设计的渠道，只断言「没抛异常」毫无鉴别力——
     * 凭据错、格式错、网关卡错，`push()` 同样一声不吭。
     */
    record DeliveryResult(int status, String reason) {
        boolean ok() {
            return status == 200;
        }
    }

    /**
     * 真实投递冒烟入口（测试用，包内可见）：对指定设备同步投递一次并返回 APNs 响应。
     * <p>
     * 存在的意义是「换 key / 换年 / 换 App 之后怎么确认凭据还好使」——见
     * {@code ApnsLiveSmokeTest}（默认跳过，需显式给 LIVE 环境变量）。
     */
    DeliveryResult deliverNow(String userId, PushDevice device, PushMessage message) throws Exception {
        return deliver(userId, device, message, bearerToken());
    }

    /** 投递到单台设备：按 token 自带的环境选网关（侧载 = sandbox，TestFlight = production）。 */
    private DeliveryResult deliver(String userId, PushDevice device, PushMessage message, String jwt) {
        String host = PushDevice.ENV_PRODUCTION.equals(device.environment()) ? productionHost : sandboxHost;
        try {
            String body = payload(message);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/3/device/" + device.token()))
                    .timeout(Duration.ofSeconds(10))
                    .header("authorization", "bearer " + jwt)
                    .header("apns-topic", device.bundleId() != null && !device.bundleId().isBlank()
                            ? device.bundleId() : bundleId)
                    .header("apns-push-type", "alert")
                    .header("apns-priority", "10")
                    .header("apns-expiration", String.valueOf(Instant.now().getEpochSecond() + EXPIRATION_SECONDS))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 200) {
                log.info("APNs 推送成功 | type={} | env={}", message.type(), device.environment());
                return new DeliveryResult(200, null);
            }
            if (code == 410) {
                // 设备已卸载 App / token 失效：清理登记，避免此后每次推送都白跑一趟
                boolean removed = deviceRepository.remove(userId, device.token());
                log.warn("APNs token 已失效（410 Unregistered），已清理登记 | removed={}", removed);
                return new DeliveryResult(410, "Unregistered");
            }
            String reason = reason(resp.body());
            log.warn("APNs 推送失败 | status={} | reason={} | env={} | {}", code, reason,
                    device.environment(), hint(code, reason));
            return new DeliveryResult(code, reason);
        } catch (InterruptedException e) {
            // 同 Bark/WeChat（P3，2026-08-17）：不置 interrupt 标志污染共享调度线程
            log.info("APNs 推送被中断（服务关闭中）| type={}", message.type());
            return new DeliveryResult(-1, "interrupted");
        } catch (Exception e) {
            log.warn("APNs 推送异常 | type={} | {}", message.type(), e.getMessage());
            return new DeliveryResult(-1, e.getMessage());
        }
    }

    /**
     * 组装 APS 负载：title/body 走 Jackson 序列化。
     * <p>
     * <b>必须用 Jackson 而不是手拼字符串</b>：本项目已经吃过一次「多行 LLM 正文里的真实换行
     * 进入 JSON 字面量 → 服务端 400 丢弃」的事故（Bark，2026-08-26，见 BarkPushChannelTest）。
     * 阿呆的推送正文就是 LLM 生成的多行中文，这里不再手写转义。
     * <p>
     * {@code thread-id} = 推送类型：同一类推送在通知中心归组（止损一堆、复盘一堆），不互相淹没；
     * 不设 {@code apns-collapse-id}——折叠会让后一条顶掉前一条，对「今天触发了哪几只止损」是信息损失。
     */
    String payload(PushMessage message) throws IOException {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("title", message.title() != null ? message.title() : "阿呆");
        // D1（2026-09-13 外部视角审查）：APNs 是**锁屏可见**的 alert 通知 → 渲染锁屏精简正文；
        // 完整正文（含持仓名称/现价）留给站内 Feed。见 PushChannel.PushMessage#notificationContent。
        alert.put("body", message.notificationContent() != null ? message.notificationContent() : "");
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("alert", alert);
        aps.put("sound", "default");
        if (message.type() != null && !message.type().isBlank()) {
            aps.put("thread-id", "adai-" + message.type());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("aps", aps);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * APNs provider token（ES256 JWT），带缓存。
     * <p>
     * Apple 要求：iat 在当前时间 1 小时内，且同一 token 至少每小时换一次；
     * 但**刷新频率不能高于每 20 分钟一次**（超频会返回 TooManyProviderTokenUpdates）。
     * 故缓存 40 分钟重签一次。
     */
    private String bearerToken() throws Exception {
        long now = Instant.now().getEpochSecond();
        String cached = cachedJwt;
        if (cached != null && now - cachedJwtAt < JWT_TTL_SECONDS) return cached;
        synchronized (jwtLock) {
            if (cachedJwt != null && now - cachedJwtAt < JWT_TTL_SECONDS) return cachedJwt;
            String header = b64url("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}");
            String claims = b64url("{\"iss\":\"" + teamId + "\",\"iat\":" + now + "}");
            String signingInput = header + "." + claims;
            String jwt = signingInput + "." + b64url(signEs256(signingInput));
            cachedJwt = jwt;
            cachedJwtAt = now;
            return jwt;
        }
    }

    /** ES256 签名：Java 的 ECDSA 输出 DER，JOSE 要求定长 R||S（各 32 字节）——必须转换。 */
    private byte[] signEs256(String signingInput) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return derToJose(sig.sign(), 32);
    }

    /**
     * DER(SEQUENCE{INTEGER r, INTEGER s}) → JOSE 定长 R||S。
     * <p>
     * 这个转换是 APNs 接入最常见的静默失败点：直接送 DER 给 APNs 会得到
     * {@code InvalidProviderToken}（401），而且报错完全不提示是签名格式问题。
     * DER 的 INTEGER 是**有符号大端**——最高位为 1 时会多一个 0x00 前导字节，
     * r/s 还可能不足 32 字节，所以两侧都要做定长规范化（去前导 0、左侧补 0）。
     */
    static byte[] derToJose(byte[] der, int partLen) {
        int idx = 0;
        if (der.length < 8 || der[idx++] != 0x30) throw new IllegalArgumentException("非法 DER(ECDSA) 签名");
        int seqLen = der[idx++] & 0xFF;
        if ((seqLen & 0x80) != 0) idx += seqLen & 0x7F; // 长形式长度（ES256 不会走到）
        if (der[idx++] != 0x02) throw new IllegalArgumentException("非法 DER(ECDSA) 签名：缺 r");
        int rLen = der[idx++] & 0xFF;
        int rOff = idx;
        idx += rLen;
        if (idx >= der.length || der[idx++] != 0x02) throw new IllegalArgumentException("非法 DER(ECDSA) 签名：缺 s");
        int sLen = der[idx++] & 0xFF;
        int sOff = idx;
        byte[] out = new byte[partLen * 2];
        copyFixed(der, rOff, rLen, out, 0, partLen);
        copyFixed(der, sOff, sLen, out, partLen, partLen);
        return out;
    }

    private static void copyFixed(byte[] src, int off, int len, byte[] dst, int dstOff, int partLen) {
        while (len > 1 && src[off] == 0x00) { // 去 DER 的有符号前导 0
            off++;
            len--;
        }
        if (len > partLen) throw new IllegalArgumentException("ECDSA 分量超长: " + len);
        System.arraycopy(src, off, dst, dstOff + (partLen - len), len);
    }

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** 解析 .p8（PKCS#8 PEM）→ EC 私钥；任何异常都返回 null（构造时已落 error 日志）。 */
    private PrivateKey loadPrivateKey() {
        if (!enabledFlag || keyPath.isBlank() || keyId.isBlank() || teamId.isBlank()) return null;
        try {
            String pem = Files.readString(Path.of(keyPath), StandardCharsets.UTF_8);
            String base64 = pem.replaceAll("-----BEGIN PRIVATE KEY-----", "")
                    .replaceAll("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            if (base64.isEmpty()) return null;
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            log.error("APNs .p8 私钥加载失败 | key-path={} | {}", keyPath, e.getMessage());
            return null;
        }
    }

    private boolean typeAllowed(String type) {
        if (typeAllowlist.isEmpty()) return true;
        return type != null && typeAllowlist.contains(type);
    }

    private static List<String> parseTypes(String types) {
        if (types == null || types.isBlank()) return List.of();
        return java.util.Arrays.stream(types.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** 从 APNs 错误体里取 reason（如 BadDeviceToken / InvalidProviderToken）。 */
    private static String reason(String body) {
        if (body == null || body.isBlank()) return "-";
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode r = node.get("reason");
            return r != null ? r.asText() : "-";
        } catch (Exception e) {
            return body.length() > 120 ? body.substring(0, 120) : body;
        }
    }

    /**
     * 把 APNs 的 reason 翻译成「下一步该干什么」——这些错误的自解释性很差，
     * 而排查成本全落在人身上（尤其 BadDeviceToken 的两套网关混淆）。
     */
    private static String hint(int code, String reason) {
        if ("BadDeviceToken".equals(reason) || "DeviceTokenNotForTopic".equals(reason)) {
            return "提示：token 与网关/环境不匹配（侧载= sandbox，TestFlight/上架= production）或 apns-topic 不对";
        }
        if ("InvalidProviderToken".equals(reason) || code == 403) {
            return "提示：检查 .p8 是否与 key-id/team-id 配套（同一把 key）";
        }
        if ("ExpiredProviderToken".equals(reason)) {
            return "提示：服务器时间是否偏移（JWT 的 iat 依赖系统时钟）";
        }
        if ("Unregistered".equals(reason)) {
            return "提示：设备已删除 App，应清理该 token 登记";
        }
        return "";
    }
}
