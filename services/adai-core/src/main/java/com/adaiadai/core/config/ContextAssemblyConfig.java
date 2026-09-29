package com.adaiadai.core.config;

import com.adaiadai.core.kernel.context.policy.ContextAssemblyPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ContextAssemblyConfig — 上下文装配策略 bean（RFC 20260929 批 1）。
 * <p>
 * 跟随本项目既有惯例：构造/方法级 {@code @Value} 注入（项目无 {@code @ConfigurationProperties} 类）。
 * 全部键**带默认值**，不改 {@code .env}、不改 {@code application.yml} 也能启动。
 *
 * <h3>灰度与回滚</h3>
 * {@code adai.context.assembly-mode} 默认 {@code legacy}（现状行为，逐字不变）；
 * 切 {@code v1} 启用批 1 新口径。两者是<b>同一份代码的两种行为</b>，可随时对比；
 * 回滚 = 改回 {@code legacy} + 重启（{@code @Value} 为启动期读取）。
 */
@Configuration
public class ContextAssemblyConfig {

    /**
     * 装配策略装配点。
     *
     * @param mode         {@code legacy}（默认，现状）/ {@code v1}（批 1 新口径）
     * @param shortMax     短档轮数上限
     * @param midMax       中档轮数上限
     * @param coreMax      短档核心记忆条数
     * @param coreTokens   短档核心记忆 token 上限
     * @param fallbackMax  无标签回退的相关记录条数
     */
    @Bean
    public ContextAssemblyPolicy contextAssemblyPolicy(
            @Value("${adai.context.assembly-mode:legacy}") String mode,
            @Value("${adai.context.tier.short-max-turns:3}") int shortMax,
            @Value("${adai.context.tier.mid-max-turns:9}") int midMax,
            @Value("${adai.context.core-memory-max:2}") int coreMax,
            @Value("${adai.context.core-memory-max-tokens:200}") int coreTokens,
            @Value("${adai.context.fallback-recent-max:2}") int fallbackMax) {

        boolean v1 = mode != null && "v1".equalsIgnoreCase(mode.strip());
        if (!v1) {
            return ContextAssemblyPolicy.legacy();
        }
        return new ContextAssemblyPolicy(true, shortMax, midMax, coreMax, coreTokens, fallbackMax);
    }
}
