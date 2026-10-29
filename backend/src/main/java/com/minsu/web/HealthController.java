package com.minsu.web;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * 健康检查接口。
 *
 * <p>冒烟测试基准：前端启动后调用 {@code GET /api/health}，应返回 200 + JSON。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of(
                "status", "ok",
                "service", "minsu-agent-backend",
                "version", "0.1.0",
                "timestamp", OffsetDateTime.now().toString()
        ));
    }
}
