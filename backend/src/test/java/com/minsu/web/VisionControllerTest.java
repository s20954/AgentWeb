package com.minsu.web;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 视觉代理端点测试 —— 无 key 时返回 fallback 桩结果且结构完整。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.llm.api-key=")
class VisionControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void analyzeWithoutKeyReturnsFallback() {
        webTestClient.post()
                .uri("/api/vision/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "imageBase64", "aGVsbG8=", // 任意非空 base64 即可触发无 key 桩
                        "mimeType", "image/jpeg"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.fallback").isEqualTo(true)
                .jsonPath("$.styles").isArray()
                .jsonPath("$.styles").isNotEmpty()
                .jsonPath("$.summary").isNotEmpty();
    }
}
