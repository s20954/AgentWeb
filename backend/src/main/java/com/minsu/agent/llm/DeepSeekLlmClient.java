package com.minsu.agent.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DeepSeek LLM 客户端（OpenAI 兼容协议）。
 *
 * <p>主模型 V4.1-Flash（API 名 deepseek-flash）/ V4-Pro（deepseek-chat）fallback，
 * 通过 {@code LLM_API_KEY} 环境变量配置。未配置 key 时 {@link #isConfigured()} 返回 false，
 * AgentService 据此降级到规则桩，保证骨架无 key 也能跑通。
 */
@Component
public class DeepSeekLlmClient implements LlmClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;

    public DeepSeekLlmClient(
            @Value("${app.llm.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${app.llm.api-key:}") String apiKey,
            @Value("${app.llm.model:deepseek-chat}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<Map<String, Object>> tools) {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM_API_KEY 未配置，无法调用 DeepSeek（应走降级桩）");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", toApiMessages(messages));
        body.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        JsonNode message = postChat(body);
        String content = message.path("content").isNull() ? null : message.path("content").asText();
        List<Map<String, Object>> toolCalls = parseToolCalls(message);

        return new LlmResponse(content, toolCalls);
    }

    /**
     * 视觉模型调用（OpenAI 兼容 vision 消息格式）：根据图片推断用户偏好。
     *
     * <p>content 为数组 [{type:text,...},{type:image_url,image_url:{url:data:...}}]，
     * 复用同一套 apiKey/model，仅消息体不同。未配置 key 时抛异常（调用方应走降级桩）。
     *
     * @param imageBase64 图片 base64（不含 data: 前缀）
     * @param mimeType    图片 MIME 类型，如 image/jpeg
     * @param prompt      视觉分析提示词
     * @return 模型文本输出（约定为 JSON 字符串）
     */
    public String chatVision(String imageBase64, String mimeType, String prompt) {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM_API_KEY 未配置，无法调用视觉模型（应走降级桩）");
        }

        Map<String, Object> textPart = Map.of("type", "text", "text", prompt);
        Map<String, Object> imagePart = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:" + mimeType + ";base64," + imageBase64));
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", List.of(textPart, imagePart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(userMessage));
        body.put("stream", false);

        JsonNode message = postChat(body);
        return message.path("content").isNull() ? null : message.path("content").asText();
    }

    /** 发送 /chat/completions 请求并返回 choices[0].message。 */
    private JsonNode postChat(Map<String, Object> body) {
        JsonNode resp = webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(60))
                .block();

        if (resp == null || !resp.path("choices").isArray() || resp.path("choices").isEmpty()) {
            throw new IllegalStateException("DeepSeek 返回异常：" + (resp == null ? "null" : resp.toString()));
        }
        return resp.path("choices").get(0).path("message");
    }

    private List<Map<String, Object>> toApiMessages(List<LlmMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LlmMessage m : messages) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", m.role());
            if (m.content() != null) {
                map.put("content", m.content());
            }
            if ("assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                map.put("tool_calls", m.toolCalls());
            }
            // role=tool：tool_call_id 是顶层字段，用于关联 assistant 的 tool_calls
            if ("tool".equals(m.role()) && m.toolCallId() != null) {
                map.put("tool_call_id", m.toolCallId());
            }
            out.add(map);
        }
        return out;
    }

    private List<Map<String, Object>> parseToolCalls(JsonNode message) {
        JsonNode tc = message.path("tool_calls");
        List<Map<String, Object>> result = new ArrayList<>();
        if (tc.isArray()) {
            for (JsonNode call : tc) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", call.path("id").asText());
                m.put("type", call.path("type").asText("function"));
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", call.path("function").path("name").asText());
                // arguments 是 JSON 字符串
                String argsJson = call.path("function").path("arguments").asText();
                fn.put("arguments", argsJson);
                m.put("function", fn);
                result.add(m);
            }
        }
        return result;
    }
}
