package com.adaiadai.core.infrastructure.push;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.PushDeviceFileRepository;
import com.adaiadai.core.kernel.push.PushChannel;
import com.adaiadai.core.kernel.push.PushDevice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApnsPushChannel — 阿呆自有 APNs 渠道测试（RFC 20260913）。
 * <p>
 * 这是本批最需要测试兜底的一环：APNs 的失败模式普遍无自解释性——DER/ES256 签名格式错了
 * 只回一个 {@code InvalidProviderToken}，token 送错网关只回 {@code BadDeviceToken}。
 * 所以这里不只测「不抛错」，而是**真的用公钥验签 + 真的断言 HTTP 请求形态**：
 * <ul>
 *   <li>JWT：三段结构、header alg/kid、claims iss/iat、**ES256 签名可被对应公钥验证**</li>
 *   <li>DER → JOSE（R||S 定长）转换：手搓含前导 0 与不足 32 字节的边界，这是最常见的静默失败点</li>
 *   <li>请求：路径 /3/device/{token}、apns-topic、apns-push-type、bearer 头、apns-expiration</li>
 *   <li>负载：多行中文正文序列化后仍是合法 JSON（Bark 2026-08-26 事故的同型防护）</li>
 *   <li>环境分流：sandbox / production 两台网关按 token 自带环境选，送错方向要能被测出来</li>
 *   <li>410 Unregistered → 自动清理失效登记（否则此后每次推送都白跑）</li>
 *   <li>灰度白名单：白名单外的类型不投递（第一刀只切 close-summary + learn-review 的闸门）</li>
 * </ul>
 */
class ApnsPushChannelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "c".repeat(64);
    private static final String KEY_ID = "ABCD123456";
    private static final String TEAM_ID = "4G3D37YKSB";
    private static final String BUNDLE_ID = "com.adaiadai.adaiApp";

    @TempDir
    Path tempDir;

    private KeyPair keyPair;
    private Path keyFile;
    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final PushDeviceFileRepository repo = new PushDeviceFileRepository(storage);
    private final List<HttpServer> servers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = gen.generateKeyPair();
        keyFile = tempDir.resolve("AuthKey_" + KEY_ID + ".p8");
        // 与 Apple 下发的 .p8 同格式：PKCS#8 PEM，每行 64 字符
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(keyFile, pem, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        for (HttpServer s : servers) s.stop(0);
    }

    // ── 渠道可用性 ──

    @Test
    void noKeyPath_disabled() {
        ApnsPushChannel channel = channel("", KEY_ID, TEAM_ID, "", "http://127.0.0.1:1", "http://127.0.0.1:1");
        assertFalse(channel.enabled(), "未配 key-path 应不可用");
        // 不可用时 push 静默跳过，不抛错（同 Bark 未配 key 的先例）
        channel.push("adai", message("close-summary"));
    }

    @Test
    void missingKeyIdOrTeamId_disabled() {
        assertFalse(channel(keyFile.toString(), "", TEAM_ID, "", "http://127.0.0.1:1", "http://127.0.0.1:1").enabled(),
                "缺 key-id 不可用");
        assertFalse(channel(keyFile.toString(), KEY_ID, "", "", "http://127.0.0.1:1", "http://127.0.0.1:1").enabled(),
                "缺 team-id 不可用");
    }

    @Test
    void enabledFlagFalse_disabled() {
        ApnsPushChannel channel = new ApnsPushChannel(repo, keyFile.toString(), KEY_ID, TEAM_ID, BUNDLE_ID,
                false, "", "http://127.0.0.1:1", "http://127.0.0.1:1");
        assertFalse(channel.enabled(), "显式关闭应不可用");
    }

    @Test
    void badKeyFile_disabledAndNotThrowing() {
        ApnsPushChannel channel = channel(tempDir.resolve("不存在.p8").toString(), KEY_ID, TEAM_ID, "",
                "http://127.0.0.1:1", "http://127.0.0.1:1");
        assertFalse(channel.enabled(), "私钥文件不存在应不可用（不抛异常，服务照常启动）");
        channel.push("adai", message("close-summary"));
    }

    @Test
    void garbageKeyFile_disabled() throws Exception {
        Path bad = tempDir.resolve("bad.p8");
        Files.writeString(bad, "-----BEGIN PRIVATE KEY-----\n不是密钥\n-----END PRIVATE KEY-----\n");
        assertFalse(channel(bad.toString(), KEY_ID, TEAM_ID, "", "http://127.0.0.1:1", "http://127.0.0.1:1").enabled());
    }

    @Test
    void validKey_enabledWithStatusDetail() {
        ApnsPushChannel channel = channel(keyFile.toString(), KEY_ID, TEAM_ID, "", "http://127.0.0.1:1", "http://127.0.0.1:1");
        assertTrue(channel.enabled(), "配齐 key-path/key-id/team-id 应可用");
        assertEquals("apns", channel.name());

        var status = channel.status();
        assertEquals("apns", status.get("name"));
        assertEquals(true, status.get("enabled"));
        assertEquals(true, status.get("configured"));
        assertEquals(KEY_ID, status.get("keyId"));
        assertEquals(BUNDLE_ID, status.get("bundleId"));
        assertEquals("全部", status.get("typeAllowlist"), "未配白名单即全量");
    }

    // ── 投递形态（本地假网关）──

    @Test
    void push_sendsApnsShapedRequest_toSandboxGateway() throws Exception {
        FakeApns sandbox = fakeApns(200, "{}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));

        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), "http://127.0.0.1:1")
                .push("adai", message("close-summary"));

        assertEquals(1, sandbox.requests.size(), "应投递到 sandbox 网关一次");
        Captured req = sandbox.requests.get(0);
        assertEquals("POST", req.method);
        assertEquals("/3/device/" + TOKEN, req.path, "路径必须是 /3/device/{token}");
        assertEquals(BUNDLE_ID, req.header("apns-topic"));
        assertEquals("alert", req.header("apns-push-type"));
        assertEquals("10", req.header("apns-priority"));
        assertEquals("application/json", req.header("content-type"));
        assertNotNull(req.header("apns-expiration"), "应带过期时间（0 = APNs 不代为存储，离线会丢）");
        assertTrue(Long.parseLong(req.header("apns-expiration")) > Instant.now().getEpochSecond(),
                "过期时间应是将来的时间戳");
        assertTrue(req.header("authorization").startsWith("bearer "), "应带 bearer JWT");
    }

    @Test
    void payload_multilineChinese_staysValidJson() throws Exception {
        FakeApns sandbox = fakeApns(200, "{}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));

        String multiLine = "收盘小结：\n1. 今日成交 2 笔\n2. 破止损「立昂微」\n路径 C:\\tmp";
        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), "http://127.0.0.1:1")
                .push("adai", new PushChannel.PushMessage("收盘小结", multiLine, "close-summary",
                        null, null, LocalTime.of(15, 30)));

        String body = sandbox.requests.get(0).body;
        // 关键：序列化后不得出现裸换行（Bark 2026-08-26 事故同型防护）
        assertFalse(body.contains("\n") || body.contains("\r") || body.contains("\t"),
                "JSON body 不得含裸换行/制表符: " + body);
        JsonNode aps = MAPPER.readTree(body).get("aps");
        assertEquals("收盘小结", aps.get("alert").get("title").asText());
        assertEquals(multiLine, aps.get("alert").get("body").asText(), "换行语义应原样保留");
        assertEquals("default", aps.get("sound").asText());
        assertEquals("adai-close-summary", aps.get("thread-id").asText(), "同类推送应归组");
    }

    @Test
    void payload_usesLockScreenContent_notFullContent() throws Exception {
        // D1（2026-09-13 首轮外部视角审查拍板 A）：APNs 是**锁屏可见**的 alert 通知 →
        // 渲染锁屏精简正文；完整正文（逐只持仓名称/现价）只留在站内 Feed。
        FakeApns sandbox = fakeApns(200, "{}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));

        String full = "📋 收盘小结\n持仓 2 只：\n· 京东方A 现价 5.46\n· 贵州茅台 现价 1420.00";
        String lockScreen = "今日成交 1 笔 · 1 只破止损\n打开阿呆看详情";
        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), "http://127.0.0.1:1")
                .push("adai", new PushChannel.PushMessage("收盘小结", full, "close-summary",
                        null, null, LocalTime.of(15, 30), lockScreen));

        JsonNode alert = MAPPER.readTree(sandbox.requests.get(0).body).get("aps").get("alert");
        assertEquals(lockScreen, alert.get("body").asText(), "锁屏必须用精简正文");
        assertFalse(alert.get("body").asText().contains("京东方A"), "锁屏不得泄漏持仓名: " + alert);
        assertFalse(alert.get("body").asText().contains("1420"), "锁屏不得泄漏现价: " + alert);
    }

    @Test
    void push_productionDevice_goesToProductionGateway() throws Exception {
        FakeApns sandbox = fakeApns(200, "{}");
        FakeApns production = fakeApns(200, "{}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_PRODUCTION));

        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), production.baseUrl())
                .push("adai", message("close-summary"));

        assertEquals(0, sandbox.requests.size(), "production token 不得送 sandbox 网关（会 BadDeviceToken）");
        assertEquals(1, production.requests.size(), "production token 应送生产网关");
    }

    @Test
    void push_multipleDevices_deliversToEach() throws Exception {
        FakeApns sandbox = fakeApns(200, "{}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));
        repo.save("adai", device("d".repeat(64), PushDevice.ENV_SANDBOX));

        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), "http://127.0.0.1:1")
                .push("adai", message("close-summary"));

        assertEquals(2, sandbox.requests.size(), "同一用户两台设备都应收到");
    }

    @Test
    void noDevice_noRequest() throws Exception {
        FakeApns sandbox = fakeApns(200, "{}");
        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), "http://127.0.0.1:1")
                .push("adai", message("close-summary"));
        assertEquals(0, sandbox.requests.size(), "未登记设备不应发请求");
    }

    // ── 失败路径 ──

    @Test
    void status410_removesStaleDevice() throws Exception {
        FakeApns sandbox = fakeApns(410, "{\"reason\":\"Unregistered\"}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));

        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), "http://127.0.0.1:1")
                .push("adai", message("close-summary"));

        assertTrue(repo.findByUser("adai").isEmpty(), "410 应清理失效登记，防此后每次推送都白跑");
    }

    @Test
    void status400_badDeviceToken_keepsDeviceAndDoesNotThrow() throws Exception {
        FakeApns sandbox = fakeApns(400, "{\"reason\":\"BadDeviceToken\"}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));

        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), "http://127.0.0.1:1")
                .push("adai", message("close-summary"));

        assertEquals(1, sandbox.requests.size(), "应确实发过请求（否则本用例没测到 400 路径）");
        assertEquals(1, repo.findByUser("adai").size(),
                "BadDeviceToken 不该自动删登记（可能只是环境配错，删了就再也不会自己恢复）");
    }

    @Test
    void garbageResponseBody_doesNotThrow() throws Exception {
        FakeApns sandbox = fakeApns(500, "这不是 JSON");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));
        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), "http://127.0.0.1:1")
                .push("adai", message("close-summary"));
        assertEquals(1, sandbox.requests.size());
    }

    @Test
    void gatewayUnreachable_doesNotThrow() throws Exception {
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));
        // 端口 1 上不会有服务：连接失败必须被吞掉，否则会把推送生产方的定时任务打挂
        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", "http://127.0.0.1:1", "http://127.0.0.1:1")
                .push("adai", message("close-summary"));
    }

    // ── 灰度白名单 ──

    @Test
    void typeAllowlist_otherTypesSkipped() throws Exception {
        FakeApns sandbox = fakeApns(200, "{}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));
        ApnsPushChannel channel = new ApnsPushChannel(repo, keyFile.toString(), KEY_ID, TEAM_ID, BUNDLE_ID,
                true, "close-summary,learn-review", sandbox.baseUrl(), "http://127.0.0.1:1");

        channel.push("adai", message("stop-loss"));
        assertEquals(0, sandbox.requests.size(), "白名单外的类型（止损）不该投递");

        channel.push("adai", message("close-summary"));
        assertEquals(1, sandbox.requests.size(), "白名单内的类型（收盘小结）应投递");

        channel.push("adai", message("learn-review"));
        assertEquals(2, sandbox.requests.size(), "复习提醒也应在白名单内");
    }

    @Test
    void typeAllowlist_blankMeansAll() throws Exception {
        FakeApns sandbox = fakeApns(200, "{}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));
        channel(keyFile.toString(), KEY_ID, TEAM_ID, " ", sandbox.baseUrl(), "http://127.0.0.1:1")
                .push("adai", message("stop-loss"));
        assertEquals(1, sandbox.requests.size(), "空白名单 = 全量放行");
    }

    // ── JWT / ES256 ──

    @Test
    void jwt_hasValidEs256SignatureAndClaims() throws Exception {
        FakeApns sandbox = fakeApns(200, "{}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));
        channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), "http://127.0.0.1:1")
                .push("adai", message("close-summary"));

        String jwt = sandbox.requests.get(0).header("authorization").substring("bearer ".length());
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "JWT 应为 header.payload.signature 三段");

        JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertEquals("ES256", header.get("alg").asText());
        assertEquals(KEY_ID, header.get("kid").asText(), "kid 必须是 Apple 控制台的 Key ID");

        JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals(TEAM_ID, claims.get("iss").asText(), "iss 必须是 Team ID");
        long iat = claims.get("iat").asLong();
        long now = Instant.now().getEpochSecond();
        assertTrue(Math.abs(now - iat) < 300, "iat 应接近当前时间（偏差过大 APNs 会拒）: iat=" + iat + " now=" + now);

        // 核心断言：签名必须能被这把 .p8 对应的公钥验证（DER→JOSE 转换错了这里就红）
        byte[] jose = Base64.getUrlDecoder().decode(parts[2]);
        assertEquals(64, jose.length, "ES256 JOSE 签名必须是定长 64 字节（R 32 + S 32）");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(joseToDer(jose)), "JWT 签名验签失败——DER/JOSE 转换有误");
    }

    @Test
    void jwt_reusedAcrossPushesWithinTtl() throws Exception {
        FakeApns sandbox = fakeApns(200, "{}");
        repo.save("adai", device(TOKEN, PushDevice.ENV_SANDBOX));
        ApnsPushChannel channel = channel(keyFile.toString(), KEY_ID, TEAM_ID, "", sandbox.baseUrl(), "http://127.0.0.1:1");

        channel.push("adai", message("close-summary"));
        channel.push("adai", message("learn-review"));

        assertEquals(2, sandbox.requests.size());
        // 同一 token 复用：Apple 限制「刷新不得快于每 20 分钟一次」，每条推送重签会撞 TooManyProviderTokenUpdates
        assertEquals(sandbox.requests.get(0).header("authorization"),
                sandbox.requests.get(1).header("authorization"),
                "TTL 内应复用同一 JWT，不重新签发");
    }

    @Test
    void derToJose_handlesLeadingZeroPadding() {
        // DER 的 INTEGER 是有符号大端：最高位为 1 时编码会多一个 0x00 前导字节（约 50% 概率出现）
        byte[] r = new byte[32];
        r[0] = (byte) 0x80;
        byte[] s = new byte[32];
        s[31] = 0x01;

        byte[] jose = ApnsPushChannel.derToJose(der(toIntArray(r), toIntArray(s)), 32);
        assertEquals(64, jose.length);
        assertEquals((byte) 0x80, jose[0], "前导 0x00 应被剥掉（不是当成数据保留）");
        org.junit.jupiter.api.Assertions.assertArrayEquals(r, Arrays.copyOfRange(jose, 0, 32));
        assertEquals((byte) 0x01, jose[63]);
    }

    @Test
    void derToJose_leftPadsShortComponent() {
        // 分量不足 32 字节（S 数值很小时）→ 必须右侧对齐、左侧补 0 到定长
        byte[] jose = ApnsPushChannel.derToJose(der(new int[]{0x01, 0x02, 0x03}, new int[]{0x0A, 0x0B}), 32);
        assertEquals(64, jose.length);
        assertEquals((byte) 0x01, jose[29], "R 应右对齐（左侧补 0）");
        assertEquals((byte) 0x0A, jose[62], "S 应右对齐（左侧补 0）");
    }

    @Test
    void derToJose_rejectsOversizedComponent() {
        int[] oversized = new int[33];
        oversized[0] = 0x01; // 33 字节有效分量，超出 ES256 的 32 字节定长
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ApnsPushChannel.derToJose(der(oversized, new int[]{0x01}), 32));
    }

    @Test
    void derToJose_rejectsGarbage() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ApnsPushChannel.derToJose(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}, 32));
    }

    // ── 脚手架 ──

    private ApnsPushChannel channel(String keyPath, String keyId, String teamId, String types,
                                    String sandboxHost, String productionHost) {
        return new ApnsPushChannel(repo, keyPath, keyId, teamId, BUNDLE_ID, true, types, sandboxHost, productionHost);
    }

    private static PushChannel.PushMessage message(String type) {
        return new PushChannel.PushMessage("阿呆", "正文内容", type, null, null, LocalTime.of(15, 30));
    }

    private PushDevice device(String token, String env) {
        return new PushDevice(token, PushDevice.PLATFORM_IOS, env, BUNDLE_ID, "iPhone",
                "2026-09-13T05:00:00Z", "2026-09-13T05:00:00Z");
    }

    /** 本地假 APNs 网关：HTTP/1.1（host 为 http:// 时渠道自动降级协议，见构造函数注释）。 */
    private FakeApns fakeApns(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeApns fake = new FakeApns(server);
        server.createContext("/", exchange -> {
            Captured c = new Captured();
            c.method = exchange.getRequestMethod();
            c.path = exchange.getRequestURI().getPath();
            exchange.getRequestHeaders().forEach((k, v) -> c.headers.put(k.toLowerCase(), v.isEmpty() ? "" : v.get(0)));
            c.body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            fake.requests.add(c);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        servers.add(server);
        return fake;
    }

    private static final class FakeApns {
        final HttpServer server;
        final List<Captured> requests = java.util.Collections.synchronizedList(new ArrayList<>());

        FakeApns(HttpServer server) {
            this.server = server;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }

    private static final class Captured {
        String method;
        String path;
        String body;
        final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    // DER 构造：SEQUENCE { INTEGER r, INTEGER s }
    private static byte[] der(int[] r, int[] s) {
        byte[] rEnc = derInt(r);
        byte[] sEnc = derInt(s);
        int len = rEnc.length + sEnc.length;
        byte[] out = new byte[2 + len];
        out[0] = 0x30;
        out[1] = (byte) len;
        System.arraycopy(rEnc, 0, out, 2, rEnc.length);
        System.arraycopy(sEnc, 0, out, 2 + rEnc.length, sEnc.length);
        return out;
    }

    private static byte[] derInt(int[] v) {
        int start = 0;
        while (start < v.length - 1 && v[start] == 0x00) start++;
        int n = v.length - start;
        boolean pad = (v[start] & 0x80) != 0;
        byte[] out = new byte[2 + n + (pad ? 1 : 0)];
        out[0] = 0x02;
        out[1] = (byte) (n + (pad ? 1 : 0));
        if (pad) out[2] = 0x00;
        for (int i = 0; i < n; i++) out[2 + (pad ? 1 : 0) + i] = (byte) v[start + i];
        return out;
    }

    private static int[] toIntArray(byte[] b) {
        int[] out = new int[b.length];
        for (int i = 0; i < b.length; i++) out[i] = b[i] & 0xFF;
        return out;
    }

    /** JOSE(R||S) → DER，供公钥验签（测试侧反向转换，与渠道内转换互为校验）。 */
    private static byte[] joseToDer(byte[] jose) {
        return der(toIntArray(Arrays.copyOfRange(jose, 0, 32)), toIntArray(Arrays.copyOfRange(jose, 32, 64)));
    }
}
