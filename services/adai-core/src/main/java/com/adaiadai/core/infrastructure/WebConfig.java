package com.adaiadai.core.infrastructure;

import com.adaiadai.core.infrastructure.security.AdminAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebConfig — 跨域（CORS）+ 管理端点鉴权。
 * <p>
 * CORS 来源由 {@code *} 收窄为配置化 origin 模式（REVIEW #127）：默认仅放行
 * localhost/127.0.0.1 任意端口（覆盖 {@code flutter run -d chrome} 随机端口与
 * {@code serve_web.sh} 固定 8081/8082/8083），生产由 {@code ADAI_ALLOWED_ORIGIN_PATTERNS}
 * 追加前端所在域名。
 * <p>
 * 管理端点（/api/v1/admin/**、/api/v1/accounts/**）注册 {@link AdminAuthInterceptor}，
 * 未配置令牌时 fail-closed 拒绝（防公网裸奔）。
 */
@Configuration
public class WebConfig {

    @Value("${adai.security.admin-token:}")
    private String adminToken;

    @Value("${adai.security.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOriginPatterns;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(allowedOriginPatterns.split(","))
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new AdminAuthInterceptor(adminToken))
                        .addPathPatterns("/api/v1/admin/**", "/api/v1/accounts/**")
                        // 产品端选号端点（仅 enabled 账号），需无鉴权可访问（v1.0.0 多账号前端选号提前）
                        .excludePathPatterns("/api/v1/accounts/available");
            }
        };
    }
}
