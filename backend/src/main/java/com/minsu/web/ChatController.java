package com.minsu.web;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

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
 *
 * <p>安全修复（2026-11-17）：
 * <ol>
 *   <li>userId 回退：不传 X-User-Id 时用 sessionId，避免交易工具全被拒；</li>
 *   <li>断连取消：返回的 Flux 挂 {@code doFinally(cancel)}，虚拟线程里的 Agent 每步检查取消令牌，
 *       用户关页/断网后立即停止，不再偷偷跑完下单/支付等写操作；</li>
 *   <li>有界背压缓冲：unicast 缓冲上限 {@value #SSE_BUFFER_SIZE}，超限丢最旧，防慢客户端 + 高速 token 导致 OOM。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    /** SSE 背压缓冲上限（token 级事件多，256 足够平滑突发又不至于无界增长）。 */
    private static final int SSE_BUFFER_SIZE = 256;

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
        // userId 回退：前端契约要求传 X-User-Id，但缺省时回退 sessionId 而非传 null，
        // 否则 create_order 抛 STATE_ERROR、get/cancel/pay_order 抛 BUSINESS_REJECT（交易功能全失效）
        String effectiveUserId = (userId == null || userId.isBlank()) ? sessionId : userId;

        // 有界缓冲：使用固定容量队列，满时 tryEmitNext 返回 FAIL_OVERFLOW 直接丢弃新 token，
        // 缓冲不会无界增长（防 OOM）。仅影响极端慢客户端的展示平滑度，不影响数据正确性。
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<ServerSentEvent<String>>get(SSE_BUFFER_SIZE).get());
        AtomicBoolean cancelled = new AtomicBoolean(false);

        AgentService.EventSink eventSink = new AgentService.EventSink() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void step(StepEvent event) {
                emit("step", event);
            }

            @Override
            public void text(String text) {
                emit("text", text);
            }

            private void emit(String type, Object data) {
                if (cancelled.get()) {
                    return; // 已断连，不再序列化/推送，节省开销
                }
                try {
                    String json = objectMapper.writeValueAsString(data);
                    sink.tryEmitNext(ServerSentEvent.<String>builder(json).event(type).build());
                } catch (Exception e) {
                    emitError(sink, e);
                }
            }
        };

        // 异步执行 Agent 收敛，避免阻塞 Reactor 线程
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                agentService.run(effectiveUserId, sessionId, message, eventSink);
                if (!cancelled.get()) {
                    sink.tryEmitNext(ServerSentEvent.<String>builder("{}").event("done").build());
                }
            } catch (Exception e) {
                // 取消触发的中断属正常收尾，不推 error（客户端已断开）
                if (!cancelled.get()) {
                    emitError(sink, e);
                }
            } finally {
                sink.tryEmitComplete();
            }
        });

        // 订阅取消（关页/刷新/断网）时置取消标志并中断后台线程，
        // Agent 在每步与写工具前检查 isCancelled / Thread.interrupted，立即停止。
        return sink.asFlux().doFinally(signal -> {
            if (signal == SignalType.CANCEL) {
                cancelled.set(true);
                worker.interrupt();
            }
        });
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
