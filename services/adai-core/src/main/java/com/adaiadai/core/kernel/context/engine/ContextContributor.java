package com.adaiadai.core.kernel.context.engine;

import com.adaiadai.core.kernel.record.ContentRecord;

/**
 * ContextContributor — 场景上下文贡献者（插件接口）。
 * <p>
 * Domain OS 实现此接口为特定场景注入额外上下文到 ContextPackage 中。
 * Kernel 通过此接口发现 Domain OS，不直接依赖任何 Domain 包。
 * <p>
 * 例如：Trading OS 贡献当前持仓摘要，Life OS 贡献近期生活趋势。
 *
 * @see DefaultContextContributor 通用场景回退
 */
public interface ContextContributor {

    /**
     * 是否支持处理此场景。
     *
     * @param scene 场景标识（trading / life / research / note）
     * @return true 表示此贡献者能为此场景提供上下文
     */
    boolean supports(String scene);

    /**
     * 为此场景贡献额外上下文内容。
     * <p>
     * 返回的字符串将被插入到 Prompt 中，让 AI 拥有更多领域感知。
     *
     * @param identityRef 用户身份摘要
     * @param record      当前记录
     * @return 额外的上下文内容（Markdown 格式），空字符串表示无额外内容
     */
    String enrich(String identityRef, ContentRecord record);

    /**
     * 是否为默认兜底贡献者。
     * 当没有特定场景贡献者时，使用默认贡献者。
     */
    default boolean isDefault() {
        return false;
    }
}
