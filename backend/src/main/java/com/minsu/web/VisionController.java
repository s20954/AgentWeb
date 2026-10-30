package com.minsu.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsu.agent.llm.DeepSeekLlmClient;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 视觉代理端点 —— 「看图选房」：接收图片 base64，推断用户偏好的房源风格/预算倾向。
 *
 * <p>设计要点（对齐无 key 也能跑通演示）：
 * <ul>
 *   <li>LLM 未配置 → 返回演示用规则桩（固定风格标签），并标注 {@code fallback:true}；</li>
 *   <li>LLM 已配置 → 调 DeepSeek vision 消息格式，解析模型输出的结构化 JSON 标签；</li>
 *   <li>模型调用失败/输出不可解析 → 同样回退规则桩，保证链路不中断。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/vision")
public class VisionController {

    private final DeepSeekLlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VisionController(DeepSeekLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** 请求体：图片 base64 + MIME 类型。 */
    public record VisionRequest(String imageBase64, String mimeType) {
    }

    @PostMapping("/analyze")
    public Mono<Map<String, Object>> analyze(@RequestBody(required = false) VisionRequest req) {
        if (req == null || req.imageBase64() == null || req.imageBase64().isBlank()) {
            Map<String, Object> empty = fallback();
            empty.put("summary", "未收到有效图片，已按默认偏好返回。");
            return Mono.just(empty);
        }

        if (!llmClient.isConfigured()) {
            return Mono.just(fallback());
        }

        // 视觉模型调用是阻塞 IO，不能在 WebFlux 事件循环上 block()，移到 boundedElastic
        return Mono.fromCallable(() -> {
            try {
                String content = llmClient.chatVision(req.imageBase64(), mimeType(req), visionPrompt());
                Map<String, Object> parsed = parseVisionJson(content);
                parsed.put("fallback", false);
                return parsed;
            } catch (Exception e) {
                Map<String, Object> f = fallback();
                f.put("fallback", true);
                return f;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 视觉分析提示词：要求输出结构化 JSON 标签。 */
    private String visionPrompt() {
        return """
                你是一个民宿风格分析助手。请根据图片内容，推断用户偏好的房源风格、氛围与预算倾向。
                只输出 JSON（不要 markdown 代码块），格式：
                {"styles":["设计","原木","网红","竹海","溪流","现代"中选1-3个],"budget":"低/中/中高/高 或 800-1500 这样的区间","keywords":["2-4个关键词"],"summary":"一句中文总结，如：识别到设计风、原木质感，预算中高"}
                """;
    }

    private String mimeType(VisionRequest req) {
        return req.mimeType() == null || req.mimeType().isBlank() ? "image/jpeg" : req.mimeType();
    }

    /** 演示用规则桩结果（无 key / 调用失败时返回）。 */
    private Map<String, Object> fallback() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fallback", true);
        out.put("styles", List.of("设计", "原木"));
        out.put("budget", "中高");
        out.put("keywords", List.of("现代设计", "原木质感", "简约"));
        out.put("summary", "识别到：设计风 / 原木质感 / 预算中高");
        return out;
    }

    /** 解析模型输出的 JSON（容忍 markdown 代码块包裹）。解析失败抛异常，由上层回退。 */
    private Map<String, Object> parseVisionJson(String content) throws Exception {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("视觉模型返回空内容");
        }
        String json = content.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        JsonNode node = objectMapper.readTree(json);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("styles", toStringList(node.path("styles")));
        out.put("budget", node.path("budget").asText(""));
        out.put("keywords", toStringList(node.path("keywords")));
        out.put("summary", node.path("summary").asText("识别到图片偏好"));
        return out;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }
}
