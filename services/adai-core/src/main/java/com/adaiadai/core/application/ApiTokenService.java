package com.adaiadai.core.application;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.auth.ApiToken;
import com.adaiadai.core.kernel.auth.ApiTokenRepository;
import com.adaiadai.core.kernel.auth.TokenScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ApiTokenService — 外部工具令牌的签发 / 校验 / 撤销（2026-09-13 外部入口批）。
 * <p>
 * 场景见 {@link ApiToken}：把「能改密码、能读全部数据」的登录会话交给快捷指令，
 * 等于把主钥匙放进一个会被分享的文件里。本服务签发的是**另一类凭据**：限权、可撤销、可辨认。
 * <p>
 * 三条实现上的取舍：
 * <ol>
 *   <li><b>明文只出现一次</b>：与会话同口径，落盘只存 SHA-256 哈希。签发响应是唯一一次
 *       能看到明文的机会；之后连我们都还原不出来（用户丢了就重新签发一把）。</li>
 *   <li><b>lastUsedAt 节流写盘</b>：校验发生在**每个请求**上，若每次都回写文件，
 *       一次整理请求就会触发多轮 RMW（而文件锁是全局的）。这里只在距上次记录超过
 *       {@link #LAST_USED_THROTTLE} 时才写——成本换到「分钟级精度足够回答它在不在用」。</li>
 *   <li><b>scope 白名单在枚举里，不在这里</b>：本服务不做任何「路径推断权限」的判断，
 *       一律问 {@link TokenScope#allows}——权限判定只有一处，避免两套规则漂移。</li>
 *   <li><b>失效不只靠手动撤销</b>（2026-09-14 增量深审 P0-2 修复）：改密 / 管理员重置密码 /
 *       禁用 / 删号都会调 {@link #revokeAll}（挂在 {@code AuthService} 的同一条安全路径上），
 *       校验时另复核账号仍在且未禁用——「改密能否踢掉泄漏的钥匙」是用户对凭据的基本预期，
 *       不能只写在注释里。</li>
 *   <li><b>到期即失效 + 按哈希撤销</b>（2026-09-14 晚间批 P1-令牌1 / S-凭据1）：新签发令牌
 *       默认 {@link ApiToken#DEFAULT_TTL_DAYS} 天到期（存量老令牌可空 = 不过期）；
 *       撤销优先按完整哈希（{@link TokenView#id}），前缀只在**唯一命中**时兼容放行——
 *       这把钥匙的明文会躺在可能被转发的 {@code .shortcut} 里，不能只靠人记得回来撤。</li>
 * </ol>
 */
@Service
public class ApiTokenService {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenService.class);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /** 明文主体长度（字节）→ 64 个十六进制字符；加前缀共 69。 */
    private static final int TOKEN_BYTES = 32;
    /** 展示用前缀保留的字符数（不含 {@code adai_}）。 */
    private static final int PREFIX_HEX_CHARS = 8;

    /** lastUsedAt 的落盘节流间隔（见类注释第 2 条）。 */
    static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(5);

    /** 备注长度上限（防止有人把整段说明粘进来）。 */
    private static final int MAX_LABEL_CHARS = 40;

    private final ApiTokenRepository repository;
    private final AccountRepository accountRepository;
    private final Clock clock;

    public ApiTokenService(ApiTokenRepository repository, AccountRepository accountRepository, Clock clock) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    /** 签发结果：明文只在这一次返回。 */
    public record IssuedToken(String plainToken, ApiToken token) {}

    /**
     * 签发一把外部令牌。
     *
     * @param userId   所属账号
     * @param label    用途备注（可空，会归一为「未命名」）
     * @param scopeIds 申请的权限；**至少要有一项**，且未知 id 会被丢弃（不报错也不放权）
     */
    public IssuedToken issue(String userId, String label, List<String> scopeIds) {
        if (userId == null || userId.isBlank()) {
            throw new AuthService.AuthException("账号信息缺失，请重新登录后再试");
        }
        Set<String> scopes = new LinkedHashSet<>();
        if (scopeIds != null) {
            for (String id : scopeIds) {
                TokenScope scope = TokenScope.of(id);
                if (scope != null) {
                    scopes.add(scope.id());
                }
            }
        }
        if (scopes.isEmpty()) {
            throw new AuthService.AuthException("至少要给它一项权限，不然它什么也做不了");
        }

        String plain = ApiToken.PLAIN_PREFIX + randomHex(TOKEN_BYTES);
        Instant now = clock.instant();
        ApiToken token = new ApiToken(
                AuthService.sha256Hex(plain),
                plain.substring(0, ApiToken.PLAIN_PREFIX.length() + PREFIX_HEX_CHARS),
                userId,
                normalizeLabel(label),
                scopes,
                now,
                null,
                // P1-令牌1/S-凭据1（2026-09-14 晚间批）：新令牌默认 90 天到期——
                // 这把钥匙的明文会被放进「可能被转发出去」的 .shortcut 文件里，
                // 不能只靠用户记得回来手动撤销。
                now.plus(Duration.ofDays(ApiToken.DEFAULT_TTL_DAYS)));
        repository.save(token);

        log.info("签发外部令牌 | userId={} | prefix={} | scopes={} | expiresAt={}",
                userId, token.tokenPrefix(), scopes, token.expiresAt());
        return new IssuedToken(plain, token);
    }

    /**
     * 校验明文令牌。
     * <p>
     * 认不出 → 空（调用方转 401）。**不做任何降级**：这里返回空只意味着「不是有效的外部令牌」，
     * 不代表「放行」——Filter 会把「会话与外部令牌都不认」判为 401。
     * <p>
     * <b>账号状态复核（2026-09-14 增量深审 backend P1-2）</b>：令牌本身有效还不够，
     * 还要**账号仍在且未禁用**——会话路径（{@code AuthService.validateAndTouch}）是 fail-closed 的，
     * 令牌路径此前不看账号状态，禁用/已删账号的旧令牌仍能打卡（含付费的 learn/confirm）。
     * 读账号失败（{@link StorageException}）**不降级为放行**：抛出去比悄悄放行安全。
     */
    public Optional<ApiToken> validate(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            return Optional.empty();
        }
        // 外部令牌一律带前缀，先做一次廉价筛选（避免把任意字符串都当 hash 去查文件）
        if (!plainToken.startsWith(ApiToken.PLAIN_PREFIX)) {
            return Optional.empty();
        }
        Optional<ApiToken> found = repository.findByTokenHash(AuthService.sha256Hex(plainToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiToken token = found.get();
        Instant now = clock.instant();
        // P1-令牌1（2026-09-14 晚间批）：到期即失效（可空 = 存量老令牌不过期）。
        // 放在账号复核之前：过期是更廉价也更常见的拒绝理由。
        if (token.isExpired(now)) {
            log.warn("外部令牌被拒：已过期 | prefix={} | expiresAt={}", token.tokenPrefix(), token.expiresAt());
            return Optional.empty();
        }
        // 账号状态复核：账号不存在或已禁用 → 视为无效凭据（与会话路径同口径 fail-closed）
        Optional<Account> account = accountRepository.findById(token.userId());
        if (account.isEmpty() || !account.get().enabled()) {
            log.warn("外部令牌被拒：账号不存在或已禁用 | prefix={} | userId={}",
                    token.tokenPrefix(), token.userId());
            return Optional.empty();
        }
        // 节流写盘（见类注释第 2 条）
        if (token.lastUsedAt() == null
                || token.lastUsedAt().isBefore(now.minus(LAST_USED_THROTTLE))) {
            try {
                repository.save(token.usedAt(now));
            } catch (RuntimeException e) {
                // 记不上「最近使用」不该让请求失败——它只影响可观测性，不影响鉴权结论
                log.warn("更新令牌最近使用时间失败（不影响本次请求） | prefix={} | {}",
                        token.tokenPrefix(), e.getMessage());
            }
        }
        return Optional.of(token);
    }

    /** 某账号的令牌列表（按签发时间倒序）。 */
    public List<ApiToken> list(String userId) {
        return repository.findByUserId(userId).stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    /**
     * 轮换令牌（REVIEW S-凭据1 剩余项，2026-09-17）：换一把新钥匙并**立刻作废旧的那把**。
     * <p>
     * 为什么不做成「先撤旧的、再签发新的」：中间有空窗，而且用户撤完忘了签发就断链
     * （快捷指令那边直接失效，还得重新配一遍）。这里一次完成，顺序固定为**先发新、再撤旧**；
     * 万一旧钥匙撤不掉，就把新钥匙也撤掉——**两把同时有效比断链更糟**（分不清哪把外泄了）。
     * <p>
     * 前缀参数与撤销同口径：**非唯一命中不接受**（一次换两把是静默误操作）。
     *
     * @return 新令牌（明文只此一次）；旧令牌找不到 / 不属于该账号 / 前缀歧义 → 空
     */
    public Optional<IssuedToken> rotate(String userId, String idOrPrefix) {
        if (userId == null || userId.isBlank() || idOrPrefix == null || idOrPrefix.isBlank()) {
            return Optional.empty();
        }
        String needle = idOrPrefix.strip();
        List<ApiToken> mine = repository.findByUserId(userId);
        List<ApiToken> matched;
        if (isFullHash(needle)) {
            matched = mine.stream().filter(t -> needle.equals(t.tokenHash())).toList();
        } else {
            matched = mine.stream().filter(t -> needle.equals(t.tokenPrefix())).toList();
            if (matched.size() > 1) {
                log.warn("轮换被拒：前缀 {} 命中 {} 把（请改用完整 id） | userId={}",
                        needle, matched.size(), userId);
                return Optional.empty();
            }
        }
        if (matched.size() != 1) {
            return Optional.empty();
        }
        ApiToken old = matched.get(0);

        // 先发新（同 label、同权限）——新钥匙先到手，任何时刻都不悬空
        IssuedToken fresh = issue(userId, old.label(), List.copyOf(old.scopes()));
        // 再撤旧；撤不掉 → 连新的也撤掉，绝不留下两把同时有效
        if (!revoke(userId, old.tokenHash())) {
            revoke(userId, fresh.token().tokenHash());
            log.error("轮换失败：旧令牌撤不掉，已回滚新令牌 | userId={} | old={}", userId, old.tokenPrefix());
            throw new AuthService.AuthException("换钥匙没成功（旧的没撤掉），这次先不动它，稍后再试一次");
        }
        log.info("轮换外部令牌 | userId={} | 旧={} | 新={}", userId,
                old.tokenPrefix(), fresh.token().tokenPrefix());
        return Optional.of(fresh);
    }

    /**
     * 撤销一把令牌（限定 userId，防跨账号删除）。
     * <p>
     * <b>P1-令牌1（2026-09-14 晚间批）</b>：优先按**完整哈希**（新前端用 {@code TokenView.id}）撤销；
     * 前缀只作兼容退路，且**必须唯一命中**——旧实现直接按前缀删，
     * 同账号两把令牌前缀碰撞时会一次删掉两把（静默误撤，用户只想收回其中一把）。
     */
    public boolean revoke(String userId, String idOrPrefix) {
        if (userId == null || idOrPrefix == null || idOrPrefix.isBlank()) {
            return false;
        }
        String needle = idOrPrefix.strip();
        if (isFullHash(needle)) {
            Optional<ApiToken> mine = repository.findByTokenHash(needle)
                    .filter(t -> Objects.equals(t.userId(), userId));
            if (mine.isEmpty()) {
                return false;
            }
            boolean removed = repository.deleteByTokenHash(needle);
            if (removed) {
                log.info("撤销外部令牌 | userId={} | id={}", userId, needle);
            }
            return removed;
        }
        List<ApiToken> matched = repository.findByUserId(userId).stream()
                .filter(t -> Objects.equals(t.tokenPrefix(), needle))
                .toList();
        if (matched.size() != 1) {
            if (matched.size() > 1) {
                log.warn("撤销外部令牌被拒：前缀 {} 命中 {} 把（请改用完整 id） | userId={}",
                        needle, matched.size(), userId);
            }
            return false;
        }
        boolean removed = repository.deleteByTokenHash(matched.get(0).tokenHash());
        if (removed) {
            log.info("撤销外部令牌 | userId={} | prefix={}", userId, needle);
        }
        return removed;
    }

    /** 64 位十六进制 = SHA-256 十六进制哈希（撤销用的稳定标识）。 */
    private static boolean isFullHash(String s) {
        if (s.length() != 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** 撤销某账号全部令牌（**改密 / 管理员重置密码 / 禁用 / 删号联动**，见 {@code AuthService}）。 */
    public int revokeAll(String userId) {
        int removed = repository.deleteByUserId(userId);
        if (removed > 0) {
            log.info("撤销账号全部外部令牌 | userId={} | count={}", userId, removed);
        }
        return removed;
    }

    private static String normalizeLabel(String label) {
        if (label == null || label.isBlank()) return "未命名";
        String trimmed = label.strip();
        // P2-令牌4（2026-09-14 晚间批）：按 **codePoint** 截断——substring 会把 emoji 的代理对切成
        // 孤立代理字符落盘（命中 pitfalls「emoji 代理对」）。
        if (trimmed.codePointCount(0, trimmed.length()) <= MAX_LABEL_CHARS) {
            return trimmed;
        }
        int end = trimmed.offsetByCodePoints(0, MAX_LABEL_CHARS);
        return trimmed.substring(0, end);
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        SECURE_RANDOM.nextBytes(buf);
        return HEX.formatHex(buf);
    }

    /** 供前端展示「这把钥匙能做什么」。 */
    public static List<ScopeView> availableScopes() {
        return java.util.Arrays.stream(TokenScope.values())
                .map(s -> new ScopeView(s.id(), s.description(), s.allowedRequests()))
                .toList();
    }

    public record ScopeView(String id, String description, List<String> allowedRequests) {}

    /**
     * 令牌的对外视图：**不含明文**（明文只在签发那一次出现过）。
     * <p>
     * {@code id} = 令牌哈希（SHA-256 十六进制）：它是撤销用的**稳定标识**——前缀只有 8 位十六进制，
     * 同账号碰撞时无法区分是哪一把（P1-令牌1）；{@code expiresAt} 让界面能显示「有效期至」。
     * 哈希不可反推明文，暴露它不比暴露前缀更危险。
     */
    public record TokenView(String id, String prefix, String label, Set<String> scopes,
                            Instant createdAt, Instant lastUsedAt, Instant expiresAt) {
        public static TokenView of(ApiToken t) {
            return new TokenView(t.tokenHash(), t.tokenPrefix(), t.label(), t.scopes(),
                    t.createdAt(), t.lastUsedAt(), t.expiresAt());
        }
    }
}
