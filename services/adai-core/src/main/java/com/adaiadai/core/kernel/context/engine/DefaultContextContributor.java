package com.adaiadai.core.kernel.context.engine;

import com.adaiadai.core.kernel.record.ContentRecord;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * DefaultContextContributor — 通用场景的默认上下文贡献者。
 * <p>
 * 当没有特定 Domain OS 贡献者匹配当前场景时使用此兜底实现。
 * 不贡献额外上下文，仅使用基础 Prompt。
 */
@Component
@Order(Integer.MAX_VALUE)
public class DefaultContextContributor implements ContextContributor {

    @Override
    public boolean supports(String scene) {
        return true; // 兜底：支持所有场景
    }

    @Override
    public String enrich(String identityRef, ContentRecord record) {
        return ""; // 通用场景不贡献额外上下文
    }

    @Override
    public boolean isDefault() {
        return true;
    }
}
