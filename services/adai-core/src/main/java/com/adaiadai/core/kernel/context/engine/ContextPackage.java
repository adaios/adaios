package com.adaiadai.core.kernel.context.engine;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ContextPackage — 面向 AI 的上下文包。
 * <p>
 * Context Engine 的输出产物。包含当前场景下 AI 需要了解的全部信息：
 * <ul>
 *   <li>用户身份摘要 —— AI 理解"这个人是谁"</li>
 *   <li>当前记录 —— AI 理解"发生了什么"</li>
 *   <li>场景标识 —— AI 理解"当前在哪个领域"</li>
 *   <li>组装后的 Prompt —— 可直接发送给 LLM</li>
 * </ul>
 * <p>
 * 这是 AdaiOS 的核心数据模型：Context Always —— 所有模块通过 ContextPackage 暴露能力。
 *
 * @param scene         场景标识（trading / life / research / note）
 * @param identityRef   用户身份摘要
 * @param recordTitle   当前记录标题
 * @param recordContent 当前记录正文
 * @param recordTags    当前记录标签
 * @param relatedRefs   相关上下文参考（历史记录、记忆片段摘要）
 * @param prompt        AI 组装提示词（结合 identity + record 后的完整 Prompt）
 * @param assembledAt   组装时间
 */
public record ContextPackage(
        String scene,
        String identityRef,
        String recordTitle,
        String recordContent,
        List<String> recordTags,
        List<String> relatedRefs,
        String prompt,
        LocalDateTime assembledAt
) {

    /**
     * 创建一个简单的上下文包（只有当前记录，无相关上下文）。
     */
    public static ContextPackage simple(
            String scene, String identityRef,
            String recordTitle, String recordContent, List<String> recordTags,
            String prompt) {
        return new ContextPackage(
                scene, identityRef,
                recordTitle, recordContent, recordTags,
                List.of(), prompt,
                LocalDateTime.now());
    }

    /**
     * 返回上下文包的 Token 预估量（粗略：1 token ≈ 2 中文字符）。
     */
    public int estimateTokens() {
        int total = (identityRef != null ? identityRef.length() : 0)
                + (recordContent != null ? recordContent.length() : 0)
                + (prompt != null ? prompt.length() : 0);
        return total / 2;
    }
}
