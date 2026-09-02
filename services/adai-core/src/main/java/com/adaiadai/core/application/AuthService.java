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
    private final BCryptPasswordEncoder passwordEncoder;

    /** 登录失败限流：key = ip + "|" + account → {failCount, lockUntil}。重启清零（个人站点可接受）。 */
    private final Map<String, RateLimit> rateLimits = new ConcurrentHashMap<>();

    /** 连续失败阈值与锁定时长。 */
    static final int MAX_FAILURES = 5;
    static final long LOCK_SECONDS = 15 * 60;

    public AuthService(AccountRepository accountRepository, SessionRepository sessionRepository) {
        this.accountRepository = accountRepository;
        this.sessionRepository = sessionRepository;
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
                now.plusSeconds(Session.DEFAULT_TTL_SECONDS));
        sessionRepository.save(session);
        log.info("登录成功: {} from {}", acct.userId(), clientIp);
        return new LoginResult(token, acct.userId(), acct.role(), acct.plugins(),
                session.expiresAt());
    }

    /** 登出：删除指定 token 对应会话。 */
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessionRepository.deleteByTokenHash(sha256Hex(token));
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
     * 改密：校验旧密码 → 更新哈希 → 踢除该账号其他会话（保留当前 token 的会话）。
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
        log.info("改密成功: {} 踢除其他会话 {} 个", acct.userId(), removed);
        return removed;
    }

    /**
     * 踢除某账号全部会话（REVIEW #178：admin 在 /accounts PATCH 重置他人密码后调用，
     * 被重置者需重新登录；不依赖调用方会话，保留逻辑与 changePassword 的「保留当前」相反）。
     *
     * @return 被踢除的会话数
     */
    public int kickSessions(String userId) {
        int removed = 0;
        for (Session s : sessionRepository.findByUserId(userId)) {
            sessionRepository.deleteByTokenHash(s.tokenHash());
            removed++;
        }
        if (removed > 0) {
            log.info("踢除会话: userId={} 共 {} 个", userId, removed);
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

    /** 登录结果（token 明文只此一次）。 */
    public record LoginResult(String token, String userId, String role, List<String> plugins,
                              Instant expiresAt) {}

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
