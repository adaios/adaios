package com.adaiadai.core.application;

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
    private final Clock clock;

    public ApiTokenService(ApiTokenRepository repository, Clock clock) {
        this.repository = repository;
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
        ApiToken token = new ApiToken(
                AuthService.sha256Hex(plain),
                plain.substring(0, ApiToken.PLAIN_PREFIX.length() + PREFIX_HEX_CHARS),
                userId,
                normalizeLabel(label),
                scopes,
                clock.instant(),
                null);
        repository.save(token);

        log.info("签发外部令牌 | userId={} | prefix={} | scopes={}", userId, token.tokenPrefix(), scopes);
        return new IssuedToken(plain, token);
    }

    /**
     * 校验明文令牌。
     * <p>
     * 认不出 → 空（调用方转 401）。**不做任何降级**：这里返回空只意味着「不是有效的外部令牌」，
     * 不代表「放行」——Filter 会把「会话与外部令牌都不认」判为 401。
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

    /** 按前缀撤销（限定 userId，防跨账号删除）。 */
    public boolean revoke(String userId, String prefix) {
        boolean removed = repository.deleteByPrefix(userId, prefix);
        if (removed) {
            log.info("撤销外部令牌 | userId={} | prefix={}", userId, prefix);
        }
        return removed;
    }

    /** 撤销某账号全部令牌（改密联动用）。 */
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
        return trimmed.length() <= MAX_LABEL_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_LABEL_CHARS);
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

    /** 令牌的对外视图（**不含哈希**，也不含明文——明文只在签发那一次出现过）。 */
    public record TokenView(String prefix, String label, Set<String> scopes,
                            Instant createdAt, Instant lastUsedAt) {
        public static TokenView of(ApiToken t) {
            return new TokenView(t.tokenPrefix(), t.label(), t.scopes(),
                    t.createdAt(), t.lastUsedAt());
        }
    }
}
