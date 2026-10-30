package com.minsu.web;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsu.agent.AgentService;
import com.minsu.agent.step.StepEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 对话 SSE 流式端点 —— 思考链事件 + 文本 token 流式推给前端。
 *
 * <p>对齐《框架设计蓝图》第 4.3 节「AI 思考状态可视化」+ 第 4.2 节「流式输出渲染」。
 * 前端用 EventSource/fetch stream 接收，事件类型：
 * <ul>
 *   <li>{@code step} —— 思考链步骤（data 为 StepEvent JSON）</li>
 *   <li>{@code text} —— 文本片段（data 为字符串）</li>
 *   <li>{@code done} —— 结束标记</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentService agentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody(required = false) Map<String, Object> body) {

        if (body == null) {
            body = Map.of();
        }
        String message = body.get("message") == null ? "" : body.get("message").toString();
        // 会话 ID：请求体优先，其次请求头 X-Session-Id，最后回退 userId（多轮状态按此缓存）
        String sessionId = body.get("sessionId") != null
                ? body.get("sessionId").toString()
                : (userId == null || userId.isBlank() ? "default" : userId);

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();

        AgentService.EventSink eventSink = new AgentService.EventSink() {
            @Override
            public void step(StepEvent event) {
                emit("step", event);
            }

            @Override
            public void text(String text) {
                emit("text", text);
            }

            private void emit(String type, Object data) {
                try {
                    String json = objectMapper.writeValueAsString(data);
                    sink.tryEmitNext(ServerSentEvent.<String>builder(json).event(type).build());
                } catch (Exception e) {
                    emitError(sink, e);
                }
            }
        };

        // 异步执行 Agent 收敛，避免阻塞 Reactor 线程
        Thread.ofVirtual().start(() -> {
            try {
                agentService.run(sessionId, message, eventSink);
                sink.tryEmitNext(ServerSentEvent.<String>builder("{}").event("done").build());
            } catch (Exception e) {
                emitError(sink, e);
            } finally {
                sink.tryEmitComplete();
            }
        });

        return sink.asFlux();
    }

    /**
     * 推 error 事件。错误消息必须走 JSON 序列化（与 text 事件口径一致）：
     * 裸发 message 若含换行会破坏 SSE 帧格式，前端 {@code parseTextPayload} 也按 JSON 解析。
     */
    private void emitError(Sinks.Many<ServerSentEvent<String>> sink, Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        try {
            sink.tryEmitNext(ServerSentEvent.<String>builder(objectMapper.writeValueAsString(msg)).event("error").build());
        } catch (Exception ignored) {
            // 序列化一个字符串不会失败；保底不阻断主流程
        }
    }
}
