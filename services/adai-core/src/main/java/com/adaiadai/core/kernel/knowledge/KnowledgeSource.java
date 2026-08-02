package com.adaiadai.core.kernel.knowledge;

/**
 * KnowledgeSource — 结构化知识源接口（Kernel 组件）。
 * <p>
 * Knowledge 回答"我学会了什么"——经过提炼的静态知识资产，与 Identity/Memory 同级
 * 作为 Context Engine 的数据源。
 * <p>
 * 与 {@link com.adaiadai.core.kernel.context.engine.ContextContributor} 的边界：
 * <ul>
 *   <li>KnowledgeSource → "我知道了什么"（静态知识：规则、战法、知识体系）</li>
 *   <li>ContextContributor → "现在正在发生什么"（动态上下文：持仓、市场状态）</li>
 * </ul>
 * <p>
 * 所有实现会被 Context Engine 通过 {@code List<KnowledgeSource>} 自动发现和注入。
 * 未来 Life OS、Project OS 等 Domain 各自实现此接口，无需修改 Context Engine。
 */
public interface KnowledgeSource {

    /**
     * 知识源标识，如 "trading"、"life"。
     * 用于日志和调试，不参与路由逻辑。
     */
    String name();

    /**
     * 始终注入的全局摘要（1-2KB）。
     * <p>
     * 在每次请求时都会注入 AI prompt，让 AI 在任何场景下都知道该系统存在。
     * 例如：交易系统的身份声明（"我是波段交易者，不做长线"）。
     *
     * @param userId 用户 ID（多用户架构预留；纯静态知识源忽略，Life 用其读用户记忆）
     */
    String globalContext(String userId);

    /**
     * 按场景注入的知识块。
     * <p>
     * 当 ContextEngine 在特定场景下组装 prompt 时调用。
     * 例如 scene="trading" 时注入完整交易规则，scene="life" 时注入生活习惯知识。
     *
     * @param userId 用户 ID（多用户架构预留；纯静态知识源忽略）
     * @param scene  场景标识（"trading"、"life"、"decision" 等）
     * @return 知识块文本，可能为空字符串（无相关知识时）
     */
    String enrich(String userId, String scene);
}
