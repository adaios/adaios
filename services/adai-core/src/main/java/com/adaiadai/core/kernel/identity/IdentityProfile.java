package com.adaiadai.core.kernel.identity;

import java.util.List;
import java.util.Map;

/**
 * IdentityProfile — 个人档案。
 * <p>
 * 包含用户的静态偏好、AI 协作规则、基础信息。
 * 采用 File First：从 {@code data/identity/profile.md} 读取。
 *
 * @param name        用户称呼
 * @param preferences 用户偏好（语言风格、决策习惯、关注领域等）
 * @param rules       AI 协作规则（哪些事需要确认、哪些可以自主执行）
 * @param tags        用户常关注的标签/领域
 */
public record IdentityProfile(
        String name,
        Map<String, String> preferences,
        Map<String, String> rules,
        List<String> tags
) {

    /**
     * 返回 Identity 的简短摘要（用于 Context Package 头部）。
     */
    public String summary() {
        return "用户: " + name
                + ", 偏好: " + String.join("; ", preferences.values())
                + ", 标签: " + String.join(", ", tags);
    }
}
