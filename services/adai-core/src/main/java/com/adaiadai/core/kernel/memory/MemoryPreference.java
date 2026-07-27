package com.adaiadai.core.kernel.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MemoryPreference — AI 识别的用户偏好。
 * <p>
 * 由 AI 从记录中提炼出的用户偏好、习惯。
 * 示例：用户喜欢系统化分析、技术深度、直接反馈。
 *
 * @param content    偏好描述文本
 * @param confidence 置信度 (0.0 ~ 1.0)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryPreference(
        String content,
        double confidence
) {
}
