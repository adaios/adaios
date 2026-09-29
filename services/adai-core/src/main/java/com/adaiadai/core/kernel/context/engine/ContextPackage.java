package com.adaiadai.core.kernel.context.engine;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ContextPackage — 面向 AI 的上下文包。
 * <p>
 * Context Engine 的输出产物。包含当前场景下 AI 需要了解的全部信息：
 * <ul>
 *   <li>用户身份摘要 —— AI 理解"这个人是谁"</li>
 *   <li>当前记录 —— AI 理解"发生了什么"</li>
 *   <li>场景标识 —— AI 理解"当前在哪个领域"</li>
 *   <li>组装后的 Prompt —— 可直接发送给 LLM</li>
 *   <li>conversationHistory —— 多轮对话历史（QUESTON 场景）</li>
 *   <li>domainEnum —— 插件收敛后的 domain 枚举（REVIEW P2-4：CHAT 模式 system prompt 按插件收敛）</li>
 * </ul>
 * <p>
 * 这是 AdaiOS 的核心数据模型：Context Always —— 所有模块通过 ContextPackage 暴露能力。
 *
 * @param scene               场景标识（trading / life / research / note）
 * @param identityRef         用户身份摘要
 * @param recordTitle         当前记录标题
 * @param recordContent       当前记录正文
 * @param recordTags          当前记录标签
 * @param relatedRefs         相关上下文参考（历史记录、记忆片段摘要）
 * @param prompt              AI 组装提示词（结合 identity + record 后的完整 Prompt）
 * @param assembledAt         组装时间
 * @param conversationHistory 多轮对话历史（QUESTION 场景，Statement 场景为空）
 * @param domainEnum          插件收敛后的 domain 枚举文本（如 "life(生活)/trading(交易)"；无插件用户只剩 life）
 * @param stableSystem        **稳定前缀**（RFC 20260929 批 1 ④）：v1 装配下供 CHAT 模式用作**唯一一条 system**
 *                            消息（角色契约 + 身份 + 能力边界 + domain 规则），逐轮不变以便缓存命中；
 *                            legacy 为 {@code null}（消费方回落到原有 3 条 system 的组装方式）
 */
public record ContextPackage(
        String scene,
        String identityRef,
        String recordTitle,
        String recordContent,
        List<String> recordTags,
        List<String> relatedRefs,
        String prompt,
        LocalDateTime assembledAt,
        List<ChatMessage> conversationHistory,
        String domainEnum,
        String stableSystem
) {

    /** 默认 domain 枚举（全量，**不带引号**——REVIEW P1-B1：消费方各自显式包引号，避免双重引号）。 */
    private static final String DEFAULT_DOMAIN_ENUM = "life(生活)/trading(交易)";

    public ContextPackage {
        if (conversationHistory == null) conversationHistory = List.of();
        // 对抗审查 P3-1：`relatedRefs` 也要护住——`estimateTokens` 会遍历它（旧实现不遍历，故无此风险）
        if (relatedRefs == null) relatedRefs = List.of();
        if (domainEnum == null || domainEnum.isBlank()) domainEnum = DEFAULT_DOMAIN_ENUM;
    }

    /** 旧签名兼容（无 stableSystem → 按 legacy 组装，行为不变）。 */
    public ContextPackage(String scene, String identityRef,
                          String recordTitle, String recordContent, List<String> recordTags,
                          List<String> relatedRefs, String prompt, LocalDateTime assembledAt,
                          List<ChatMessage> conversationHistory, String domainEnum) {
        this(scene, identityRef, recordTitle, recordContent, recordTags,
                relatedRefs, prompt, assembledAt, conversationHistory, domainEnum, null);
    }

    /** 更旧签名兼容（无 domainEnum → 默认全量枚举，行为不变）。 */
    public ContextPackage(String scene, String identityRef,
                          String recordTitle, String recordContent, List<String> recordTags,
                          List<String> relatedRefs, String prompt, LocalDateTime assembledAt,
                          List<ChatMessage> conversationHistory) {
        this(scene, identityRef, recordTitle, recordContent, recordTags,
                relatedRefs, prompt, assembledAt, conversationHistory, null, null);
    }

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
                LocalDateTime.now(), List.of());
    }

    /**
     * 返回上下文包的 Token 预估量（粗略：1 token ≈ 2 中文字符）。
     * <p>
     * RFC 20260929 批 1 ⑤ 修正：此前只算 {@code identityRef + recordContent + prompt}，
     * **漏掉了 {@code conversationHistory}（CHAT 模式的主体）与 {@code relatedRefs}（注入的历史/记忆块）**
     * ——生产日志里那行「预估 tokens」因此长期是失真值（实测 prompt 均长 10699 字符，而日志报 3268）。
     * <p>
     * 口径：把实际会发给模型的内容都计入（含 legacy 与 v1 两种装配的并集），
     * 因此是**上界估计**而非精确值；精确值以服务端返回的 {@code usage} 为准（批 1 ⑤ 已解析留痕）。
     */
    public int estimateTokens() {
        int total = len(identityRef) + len(recordContent) + len(prompt) + len(stableSystem);
        for (String ref : relatedRefs) total += len(ref);
        for (ChatMessage msg : conversationHistory) total += len(msg.content());
        return total / 2;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * 多轮对话中的单条消息。
     */
    public record ChatMessage(String role, String content) {
        public ChatMessage {
            if (role == null || role.isBlank()) role = "user";
            if (content == null) content = "";
        }
    }
}
