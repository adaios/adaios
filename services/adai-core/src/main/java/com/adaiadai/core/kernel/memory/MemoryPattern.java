package com.adaiadai.core.kernel.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MemoryPattern — AI 识别的用户行为模式。
 * <p>
 * 由 AI 从多条记录中提炼出的长期行为规律。
 * 示例：用户面对复杂问题时倾向先建立完整体系，容易延迟执行。
 *
 * @param content    模式描述文本
 * @param confidence 置信度 (0.0 ~ 1.0)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryPattern(
        String content,
        double confidence
) {
}
