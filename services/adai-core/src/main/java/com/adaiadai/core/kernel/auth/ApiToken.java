package com.adaiadai.core.kernel.auth;

import java.time.Instant;
import java.util.Set;

/**
 * ApiToken — 外部工具令牌（2026-09-13 外部入口批）。
 * <p>
 * <b>为什么要有它</b>：快捷指令（以及将来的邮件入站、脚本、其它 app）需要向阿呆发请求，
 * 而它们的凭据只能存在**我们控制不了的地方**——快捷指令的 token 是明文写在 plist 里的，
 * 而 {@code .shortcut} 文件本身会被分享出去（调查结论，见 RFC）。把登录会话 token 交给它，
 * 等于把「能改密码、能读全部数据、能看交易」的钥匙放进一个会被转发的文件里。
 * <p>
 * 所以外部工具用**另一类凭据**：
 * <ul>
 *   <li><b>限权</b>：只能访问 {@link TokenScope} 里显式列出的端点（默认拒绝，不靠前缀匹配）</li>
 *   <li><b>可撤销</b>：随时在设置里删掉，立即失效（不需要改密码、不影响其它设备）</li>
 *   <li><b>可辨认</b>：带 {@code label} 与 {@code lastUsedAt}，能看出「这把钥匙是谁的、还在用吗」</li>
 *   <li><b>不落明文</b>：与会话同一口径，文件里只存 SHA-256 哈希</li>
 * </ul>
 * <p>
 * <b>与会话 token 的关系</b>：两者都是 {@code Authorization: Bearer <token>}，但校验通路不同
 * （会话走 {@code SessionRepository}，外部令牌走本类），且**外部令牌绝不允许访问
 * {@code /api/v1/auth/**}**——否则它就能自己签发新令牌完成提权。
 *
 * @param tokenHash   token 的 SHA-256 十六进制哈希（唯一键；明文只在签发响应里出现一次）
 * @param tokenPrefix 明文前缀（如 {@code adai_a1b2c3d4}）——用于列表展示与撤销定位，
 *                    不足以还原明文（余下 24 字符为随机）
 * @param userId      所属账号
 * @param label       用途备注（如「快捷指令」），用户自己填，便于日后辨认
 * @param scopes      权限范围 id 集合（见 {@link TokenScope}）
 * @param createdAt   签发时间
 * @param lastUsedAt  最近一次使用时间（可空 = 从未用过）；用于「这把钥匙还在用吗」
 */
public record ApiToken(String tokenHash, String tokenPrefix, String userId, String label,
                       Set<String> scopes, Instant createdAt, Instant lastUsedAt) {

    public ApiToken {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** 明文令牌前缀（可读标识），用于展示。 */
    public static final String PLAIN_PREFIX = "adai_";

    /**
     * 这个令牌能否访问该请求。
     * <p>
     * **默认拒绝**：没有任何 scope 命中就是拒绝。多 scope 之间是「或」。
     */
    public boolean allows(String method, String uri) {
        for (String scopeId : scopes) {
            TokenScope scope = TokenScope.of(scopeId);
            if (scope != null && scope.allows(method, uri)) {
                return true;
            }
        }
        return false;
    }

    /** 记录一次使用（返回新实例；调用方决定何时落盘）。 */
    public ApiToken usedAt(Instant now) {
        return new ApiToken(tokenHash, tokenPrefix, userId, label, scopes, createdAt, now);
    }
}
