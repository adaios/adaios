package com.adaiadai.core.kernel.auth;

import java.util.List;
import java.util.Locale;

/**
 * TokenScope — 外部令牌的权限范围（2026-09-13 外部入口批）。
 * <p>
 * <b>白名单制，且逐条精确匹配</b>。这里有两个刻意的取舍，都是安全取向：
 * <ol>
 *   <li><b>不做前缀匹配</b>：{@code learn:digest} 只放行显式列出的那几条端点，而不是
 *       「{@code /api/v1/learn/digest} 及其所有子路径」。前缀匹配省事，但**将来任何人新增一个
 *       子端点，旧的令牌就自动获得了它的权限**——那是一次不需要改代码就发生的权限扩张。
 *       新增端点必须显式加进这里，才会对外部令牌开放。</li>
 *   <li><b>默认拒绝</b>：未列出的路径（包括 {@code /api/v1/auth/**}、全部管理端点、
 *       全部交易与个人数据端点）一律不可访问。尤其是 {@code /api/v1/auth/**} ——
 *       若外部令牌能调到签发端点，它就能给自己造一把更大权限的钥匙，限权形同虚设。</li>
 * </ol>
 */
public enum TokenScope {

    /**
     * 交给阿呆一个链接去整理（快捷指令的主用途）。
     * <p>
     * 只包含「提交整理任务」与「查询任务状态/额度」以及「确认付费转写」——
     * 也就是一条完整的「我发链接 → 阿呆问要不要花钱 → 我说行」流程所需的最小集合。
     */
    LEARN_DIGEST("learn:digest", "整理链接（把你分享的链接交给阿呆去读）", List.of(
            "POST /api/v1/learn/digest",
            "POST /api/v1/learn/digest/confirm",
            "GET /api/v1/learn/digest/status",
            "GET /api/v1/learn/digest/quota"));

    private final String id;
    private final String description;
    private final List<String> allowed;   // "METHOD /path"，精确匹配

    TokenScope(String id, String description, List<String> allowed) {
        this.id = id;
        this.description = description;
        this.allowed = allowed;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    /** 该 scope 放行的请求（{@code "METHOD /path"} 列表，供前端展示「这把钥匙能做什么」）。 */
    public List<String> allowedRequests() {
        return allowed;
    }

    /** 是否放行该请求（方法 + 路径**精确**匹配）。 */
    public boolean allows(String method, String uri) {
        if (method == null || uri == null) return false;
        String wanted = method.toUpperCase(Locale.ROOT) + " " + uri;
        return allowed.contains(wanted);
    }

    /** 按 id 查找；未知 id 返回 null（调用方视为「无权限」，不是「全权限」）。 */
    public static TokenScope of(String id) {
        if (id == null) return null;
        for (TokenScope s : values()) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }
}
