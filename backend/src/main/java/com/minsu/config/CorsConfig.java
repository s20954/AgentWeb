package com.minsu.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * 开发期跨域配置。
 *
 * <p>前端 dev server（5173）直连后端（8080）时需要放行；
 * 生产环境由 Nginx 统一入口（同源 /api 反代），此配置可保留不影响。
 *
 * <p>注意：<b>不使用 {@code @EnableWebFlux}</b>——在 Spring Boot 项目中它会完全接管
 * WebFlux 配置，导致 Boot 的自动装配（统一错误处理、codec、application.yml 的
 * spring.webflux.* 等）失效。仅声明一个 {@code corsConfigurer} Bean 即可。
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebFluxConfigurer corsConfigurer() {
        return new WebFluxConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // 开发期放行任意来源（vite 5173 / 本地 Nginx 8088 等均可用）。
                // 生产由 Nginx 统一入口同源反代，本配置不生效也不影响。
                registry.addMapping("/api/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .maxAge(3600);
            }
        };
    }
}
