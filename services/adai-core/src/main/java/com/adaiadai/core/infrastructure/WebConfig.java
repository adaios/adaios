package com.adaiadai.core.infrastructure;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceCleanupInterceptor;
import com.adaiadai.core.infrastructure.security.AdminAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * WebConfig — 跨域（CORS）+ 管理端点鉴权。
 * <p>
 * CORS 来源由 {@code *} 收窄为配置化 origin 模式（REVIEW #127）：默认仅放行
 * localhost/127.0.0.1 任意端口（覆盖 {@code flutter run -d chrome} 随机端口与
 * {@code serve_web.sh} 固定 8081/8082/8083），生产由 {@code ADAI_ALLOWED_ORIGIN_PATTERNS}
 * 追加前端所在域名。
 * <p>
 * 双保险（2026-08-27）：除 {@code addCorsMappings}（MVC 层，正常请求）外注册
 * {@link CorsFilter}（servlet filter 层，最高优先级）——CorsFilter 在请求进入
 * DispatcherServlet 前先写 CORS 头，因此 405/404/500 等异常响应（MVC 异常解析路径
 * 不经过 addCorsMappings）也带 {@code Access-Control-Allow-Origin}，避免浏览器把
 * 「GET 打到 POST 端点」的 405 误报为 CORS policy 错误（当日线上误报根因）。
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
                // REVIEW #213：每个 HTTP 请求完成后清空 AiTraceContext（防 ThreadLocal
                // 跨请求残留 → 漏 set trace 的调用把日志落进上一个请求的用户目录）。
                registry.addInterceptor(new AiTraceCleanupInterceptor())
                        .addPathPatterns("/api/**");
                registry.addInterceptor(new AdminAuthInterceptor(adminToken))
                        .addPathPatterns("/api/v1/admin/**", "/api/v1/accounts/**")
                        // 产品端选号端点（仅 enabled 账号），需无鉴权可访问（v1.0.0 多账号前端选号提前）
                        .excludePathPatterns("/api/v1/accounts/available");
            }
        };
    }

    /** servlet filter 层 CORS（与 addCorsMappings 同配置）：异常响应（405/404/500）也带 CORS 头。 */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 直接 new（单元测试 standaloneSetup.addFilter）时 @Value 未注入 → 兜底默认白名单
        String patterns = (allowedOriginPatterns == null || allowedOriginPatterns.isBlank())
                ? "http://localhost:*,http://127.0.0.1:*" : allowedOriginPatterns;
        config.setAllowedOriginPatterns(List.of(patterns.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(1800L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
