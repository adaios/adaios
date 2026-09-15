package com.adaiadai.core.application;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.auth.Session;
import com.adaiadai.core.kernel.auth.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AuthService — 用户认证（RFC 20260901-auth-login，根治 REVIEW #179 X-User-Id 零鉴权）。
 * <p>
 * 职责：
 * <ul>
 *   <li>登录：校验账号 + bcrypt 密码 → 签发会话 token（32 字节随机 hex），落盘只存 SHA-256 哈希</li>
 *   <li>登出 / 改密（改密踢除该账号其他会话，保留当前）</li>
 *   <li>首访 setup：仅当全系统无任何账号设过密码时可用一次，为指定账号设密码</li>
 *   <li>登录限流：按 (IP+账号) 连续 5 次失败锁 15 分钟（防爆破）</li>
 * </ul>
 * 安全默认：未设密码的账号一律拒绝登录（fail-closed）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AccountRepository accountRepository;
    private final SessionRepository sessionRepository;
    private final ApiTokenService apiTokenService;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 登录失败限流：key = ip + "|" + account → {failCount, lockUntil}。重启清零（个人站点可接受）。 */
    private final Map<String, RateLimit> rateLimits = new ConcurrentHashMap<>();

    /** 连续失败阈值与锁定时长。 */
    static final int MAX_FAILURES = 5;
    static final long LOCK_SECONDS = 15 * 60;

    public AuthService(AccountRepository accountRepository, SessionRepository sessionRepository,
                       ApiTokenService apiTokenService) {
        this.accountRepository = accountRepository;
        this.sessionRepository = sessionRepository;
        this.apiTokenService = apiTokenService;
        this.passwordEncoder = new BCryptPasswordEncoder(10);
    }

    /** 启动清理过期会话（时间判定在 application 层；storage 层不取 now()——G2 守卫）。 */
    @jakarta.annotation.PostConstruct
    public void purgeExpiredSessionsOnStartup() {
        try {
            int purged = sessionRepository.purgeExpiredBefore(Instant.now());
            if (purged > 0) {
                log.info("启动清理过期会话 {} 个", purged);
            }
        } catch (Exception e) {
            // 启动清理失败不阻塞启动（鉴权时按需判定过期兜底）
            log.warn("启动清理过期会话失败: {}", e.getMessage());
        }
    }

    // ── 登录 ──

    /**
     * 登录：返回会话 token（明文只出现一次；落盘为 SHA-256 哈希）。
     *
     * @throws AuthException 账号不存在 / 未设密码 / 密码错误 / 被限流
     */
    public LoginResult login(String account, String password, String clientIp) {
        return login(account, password, clientIp, null);
    }

    /**
     * 登录（带设备信息，RFC 20260914 L2）：设备信息写入会话，供「登录设备」列表辨认。
     * 设备信息**来自客户端上报，仅供展示，不可用于安全判定**。
     */
    public LoginResult login(String account, String password, String clientIp,
                             Session.DeviceInfo device) {
        if (account == null || account.isBlank()) {
            throw new AuthException("账号不能为空");
        }
        String limitKey = rateLimitKey(clientIp, account);
        checkRateLimit(limitKey);

        Optional<Account> accountOpt = accountRepository.findById(account.trim());
        if (accountOpt.isEmpty()) {
            registerFailure(limitKey);
            throw new AuthException("账号或密码错误");
        }
        Account acct = accountOpt.get();
        if (!acct.enabled()) {
            registerFailure(limitKey);
            throw new AuthException("账号已禁用");
        }
        if (acct.passwordHash() == null || acct.passwordHash().isBlank()) {
            // 未设密码：拒绝登录（fail-closed），引导首访 setup
            throw new AuthException("该账号尚未设置密码，请先完成初始化");
        }
        if (password == null || !passwordEncoder.matches(password, acct.passwordHash())) {
            registerFailure(limitKey);
            throw new AuthException("账号或密码错误");
        }
        rateLimits.remove(limitKey);

        String token = generateToken();
        String tokenHash = sha256Hex(token);
        Instant now = Instant.now();
        Session session = new Session(tokenHash, acct.userId(), now, now,
                now.plusSeconds(Session.DEFAULT_TTL_SECONDS), device);
        sessionRepository.save(session);
        log.info("登录成功: {} from {}", acct.userId(), clientIp);
        return new LoginResult(token, acct.userId(), acct.role(), acct.plugins(),
                session.expiresAt(), session.viewId());
    }

    /** 登出：删除指定 token 对应会话。 */
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessionRepository.deleteByTokenHash(sha256Hex(token));
    }

    // ── 登录设备（会话）管理（2026-09-14 登录体验方案 RFC 20260914 L2） ──
    //
    // 为什么需要：会话是 30 天滑动续期（活跃即不过期），意味着「一旦登录过，那台设备
    // 长期有效」。此前没有任何界面能看到『哪些设备登录着』，用户唯一的补救手段是改密码
    // （踢掉全部）——代价是自己也被踢出去、且分不清是哪台设备。这两条端点把「看见 + 单点撤销」
    // 补上，与 REVIEW「外部凭据只有手动撤销一条出口」同一条思路：
    // 凭证的生命周期必须有可辨认、可撤销的出口。

    /**
     * 列出当前账号的全部**未过期**会话（GET /auth/sessions）——「哪些设备登录着」。
     * 当前 token 对应的一台标记 {@code current=true}；按最近活跃倒序。
     *
     * @return 会话视图列表；token 无效/被踢/账号禁用 → 空（Controller 401）
     */
    public Optional<List<SessionView>> listSessions(String currentToken) {
        Optional<Session> current = validSession(currentToken);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        String currentHash = current.get().tokenHash();
        List<SessionView> views = sessionRepository.findByUserId(current.get().userId()).stream()
                .filter(s -> !s.isExpired(now))
                .sorted((a, b) -> b.lastSeenAt().compareTo(a.lastSeenAt()))
                .map(s -> new SessionView(s.viewId(), s.device(), s.createdAt(),
                        s.lastSeenAt(), s.expiresAt(), s.tokenHash().equals(currentHash)))
                .toList();
        return Optional.of(views);
    }

    /**
     * 撤销一台设备的会话（DELETE /auth/sessions/{idOrPrefix}）。
     * <p>
     * 接受**完整 token 哈希**或**其前缀**（即列表里的 {@code id}）。两条拒绝路径：
     * 前缀在本账号内命中多台 → 拒绝（防误撤，与外部工具令牌撤销同口径）；
     * 命中当前设备 → 拒绝并提示改用「退出登录」（避免用户把自己静默踢下线却不明白为什么）。
     *
     * @return 撤销结果；失败时 {@code error} 是给人看的原因
     */
    public RevokeOutcome revokeSession(String currentToken, String idOrPrefix) {
        Optional<Session> current = validSession(currentToken);
        if (current.isEmpty()) {
            return RevokeOutcome.fail("会话已失效，请重新登录");
        }
        if (idOrPrefix == null || idOrPrefix.isBlank()) {
            return RevokeOutcome.fail("请指定要撤销的设备");
        }
        String key = idOrPrefix.trim().toLowerCase();
        List<Session> matched = sessionRepository.findByUserId(current.get().userId()).stream()
                .filter(s -> s.tokenHash() != null && s.tokenHash().toLowerCase().startsWith(key))
                .toList();
        if (matched.isEmpty()) {
            return RevokeOutcome.fail("没找到这台设备，可能已经退出过了");
        }
        if (matched.size() > 1) {
            return RevokeOutcome.fail("这个标识对应多台设备，请用完整的设备 id");
        }
        Session target = matched.get(0);
        if (target.tokenHash().equals(current.get().tokenHash())) {
            return RevokeOutcome.fail("这台就是当前设备；要退出它请用「退出登录」");
        }
        sessionRepository.deleteByTokenHash(target.tokenHash());
        log.info("撤销会话: userId={} 设备={}", current.get().userId(), target.viewId());
        return new RevokeOutcome(true, null);
    }

    /**
     * 取有效会话（**不续期、不写盘**）——会话管理端点用。
     * 除 token 有效外，同时复核账号仍存在且 enabled（与 validateAndTouch 同一 fail-closed 口径）。
     */
    private Optional<Session> validSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<Session> session = sessionRepository.findByTokenHash(sha256Hex(token));
        if (session.isEmpty() || session.get().isExpired(Instant.now())) {
            return Optional.empty();
        }
        Optional<Account> account = accountRepository.findById(session.get().userId());
        if (account.isEmpty() || !account.get().enabled()) {
            return Optional.empty();
        }
        return session;
    }

    // ── 会话校验（AuthInterceptor 调用） ──

    /**
     * 按 token 校验并返回会话（滑动续期：活跃会话刷新 expiresAt）。
     *
     * @return 会话；token 无效/过期 → 空
     */
    public Optional<Session> validateAndTouch(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = sha256Hex(token);
        Optional<Session> sessionOpt = sessionRepository.findByTokenHash(tokenHash);
        if (sessionOpt.isEmpty()) {
            return Optional.empty();
        }
        Session session = sessionOpt.get();
        Instant now = Instant.now();
        if (session.isExpired(now)) {
            sessionRepository.deleteByTokenHash(tokenHash);
            return Optional.empty();
        }
        // P1-1 纵深防御（RFC 20260901-auth-login / REVIEW #178）：会话有效后，实时查账号——
        // 账号已被「删除」（findById 空）或「禁用」（enabled=false）时立即删除本会话并返回 empty
        // （fail-closed）。即使管理端禁用/删除时的「踢会话」漏执行，旧会话也立刻失效，
        // 杜绝被禁用/删除账号的遗留会话在最长 30 天滑动有效期内继续读写产品端点。
        Optional<Account> accountOpt = accountRepository.findById(session.userId());
        if (accountOpt.isEmpty() || !accountOpt.get().enabled()) {
            sessionRepository.deleteByTokenHash(tokenHash);
            return Optional.empty();
        }
        // 滑动续期：仅在接近过期时写盘，避免每个请求都触发文件写
        if (session.expiresAt().isBefore(now.plusSeconds(Session.DEFAULT_TTL_SECONDS / 2))) {
            sessionRepository.save(session.touch(now));
        }
        return Optional.of(session);
    }

    /** 会话信息（GET /auth/me）。 */
    public Optional<Account> currentAccount(String token) {
        return validateAndTouch(token).flatMap(s -> accountRepository.findById(s.userId()));
    }

    /**
     * 按 userId 查账号（不触碰会话）——AuthFilter 权限判定用
     * （REVIEW #178：admin 端点要求会话账号 role=admin；role 每次实时读文件，
     * 角色变更/降级即时生效，不依赖签发时的快照）。
     */
    public Optional<Account> findAccount(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return accountRepository.findById(userId);
    }

    // ── 首访初始化 ──

    /**
     * 首访 setup：仅当全系统无任何账号设过密码时可用（一次性）。
     * 之后任何账号有密码 → 返回 false（调用方 404）。
     */
    public boolean setupInitialPassword(String account, String password) {
        List<Account> all = accountRepository.findAll();
        boolean anyPassword = all.stream().anyMatch(a -> a.passwordHash() != null && !a.passwordHash().isBlank());
        if (anyPassword) {
            return false;
        }
        String userId = account == null || account.isBlank() ? Account.SEED_ADMIN_ID : account.trim();
        Account target = accountRepository.findById(userId)
                .orElseThrow(() -> new AuthException("账号不存在: " + userId));
        if (password == null || password.length() < 8) {
            throw new AuthException("密码长度至少 8 位");
        }
        Account updated = new Account(target.userId(), target.role(), target.enabled(),
                target.createdAt(), target.plugins(), passwordEncoder.encode(password));
        accountRepository.save(updated);
        log.info("首访初始化：账号 {} 已设置密码", userId);
        return true;
    }

    /** 是否已完成初始化（任何账号有密码）——前端登录页 setup 引导判断。 */
    public boolean isInitialized() {
        return accountRepository.findAll().stream()
                .anyMatch(a -> a.passwordHash() != null && !a.passwordHash().isBlank());
    }

    // ── 改密 ──

    /**
     * 改密：校验旧密码 → 更新哈希 → 踢除该账号其他会话（保留当前 token 的会话）
     * → **撤销该账号全部外部工具令牌**（2026-09-14 增量深审 P0-2）。
     * <p>
     * 为什么改密必须撤令牌：外部令牌的明文会存在**用户控制不了的地方**（快捷指令的
     * {@code .shortcut} 文件会被分享出去）。用户「发现不对劲 → 改密码」是对凭据泄露的标准补救动作，
     * 若改密只踢会话不撤令牌，那把泄漏的钥匙就永久有效、还能继续打卡（含付费的整理确认）——
     * 「可撤销」就只剩手动一条路，而用户恰恰不知道该去撤销。
     *
     * @return 被踢除的会话数
     */
    public int changePassword(String currentToken, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new AuthException("新密码长度至少 8 位");
        }
        Session session = validateAndTouch(currentToken)
                .orElseThrow(() -> new AuthException("会话已失效，请重新登录"));
        Account acct = accountRepository.findById(session.userId())
                .orElseThrow(() -> new AuthException("账号不存在"));
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, acct.passwordHash())) {
            throw new AuthException("原密码错误");
        }
        Account updated = new Account(acct.userId(), acct.role(), acct.enabled(),
                acct.createdAt(), acct.plugins(), passwordEncoder.encode(newPassword));
        accountRepository.save(updated);
        // 踢除该账号其他会话（保留当前）
        int removed = 0;
        for (Session s : sessionRepository.findByUserId(acct.userId())) {
            if (!s.tokenHash().equals(session.tokenHash())) {
                sessionRepository.deleteByTokenHash(s.tokenHash());
                removed++;
            }
        }
        int revoked = apiTokenService.revokeAll(acct.userId());
        log.info("改密成功: {} 踢除其他会话 {} 个 / 撤销外部令牌 {} 把", acct.userId(), removed, revoked);
        return removed;
    }

    /**
     * 踢除某账号全部会话（REVIEW #178：admin 在 /accounts PATCH 重置他人密码后调用，
     * 被重置者需重新登录；不依赖调用方会话，保留逻辑与 changePassword 的「保留当前」相反）。
     * <p>
     * <b>同时撤销该账号全部外部工具令牌</b>（2026-09-14 增量深审 P0-2）：本方法的三条调用路径
     * （重置密码 / 禁用账号 / 删除账号）都是安全敏感操作，只踢会话不撤令牌等于「禁用了他，
     * 但他的快捷指令还能用」。改密路径另在 {@link #changePassword} 内撤销。
     *
     * @return 被踢除的会话数
     */
    public int kickSessions(String userId) {
        int removed = 0;
        for (Session s : sessionRepository.findByUserId(userId)) {
            sessionRepository.deleteByTokenHash(s.tokenHash());
            removed++;
        }
        int revoked = apiTokenService.revokeAll(userId);
        if (removed > 0 || revoked > 0) {
            log.info("踢除会话: userId={} 共 {} 个 / 撤销外部令牌 {} 把", userId, removed, revoked);
        }
        return removed;
    }

    // ── 内部 ──

    /** 对外暴露密码哈希（adai-admin 建号带初始密码用，RFC 20260901-auth-login）。 */
    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    private String rateLimitKey(String ip, String account) {
        return (ip == null ? "?" : ip) + "|" + account;
    }

    private void checkRateLimit(String key) {
        RateLimit rl = rateLimits.get(key);
        if (rl != null && rl.lockUntil != null && rl.lockUntil.isAfter(Instant.now())) {
            long remain = rl.lockUntil.getEpochSecond() - Instant.now().getEpochSecond();
            throw new AuthException("尝试次数过多，请 " + (remain / 60 + 1) + " 分钟后再试");
        }
    }

    private void registerFailure(String key) {
        rateLimits.compute(key, (k, rl) -> {
            RateLimit cur = rl == null ? new RateLimit(0, null) : rl;
            if (cur.lockUntil != null && cur.lockUntil.isAfter(Instant.now())) {
                return cur;
            }
            int count = cur.failCount + 1;
            return count >= MAX_FAILURES
                    ? new RateLimit(0, Instant.now().plusSeconds(LOCK_SECONDS))
                    : new RateLimit(count, null);
        });
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** SHA-256 十六进制（token 落盘哈希；AdminAuthInterceptor 同款 MessageDigest 常量时间比较思想）。 */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 登录结果（token 明文只此一次）。{@code sessionId} = 本次会话的短标识（token 哈希前 8 位）。 */
    public record LoginResult(String token, String userId, String role, List<String> plugins,
                              Instant expiresAt, String sessionId) {}

    /**
     * 登录设备视图（GET /auth/sessions）。
     *
     * @param id         会话短标识（token 哈希前 8 位）——撤销时用它
     * @param device     签发时的设备信息（可空：老会话或客户端未上报）
     * @param current    是否为当前请求所用的会话
     */
    public record SessionView(String id, Session.DeviceInfo device, Instant createdAt,
                              Instant lastSeenAt, Instant expiresAt, boolean current) {}

    /** 撤销会话结果；{@code removed=false} 时 {@code error} 为给人看的原因。 */
    public record RevokeOutcome(boolean removed, String error) {
        static RevokeOutcome fail(String message) {
            return new RevokeOutcome(false, message);
        }
    }

    /** 限流状态。 */
    private static final class RateLimit {
        final int failCount;
        final Instant lockUntil;

        RateLimit(int failCount, Instant lockUntil) {
            this.failCount = failCount;
            this.lockUntil = lockUntil;
        }
    }

    /** 认证业务异常（401 语义，GlobalExceptionHandler 兜底映射）。 */
    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }
}
