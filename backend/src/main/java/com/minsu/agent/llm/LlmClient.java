package com.minsu.agent.llm;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM 客户端接口 —— 抽象真实 DeepSeek 与降级桩。
 *
 * <p>AgentService 只依赖此接口，不关心具体是真实 API 还是 mock。
 */
public interface LlmClient {

    /**
     * 一次 LLM 补全调用（含工具调用能力）。
     *
     * @param messages 对话消息（system/user/assistant/tool）
     * @param tools    可用的工具 Schema（Function Calling 定义），可为空
     * @return 模型回复
     */
    LlmResponse chat(List<LlmMessage> messages, List<Map<String, Object>> tools);

    /**
     * 流式补全：模型每吐出一个文本片段就回调 {@code onToken}，返回拼接后的完整文本。
     *
     * <p>默认实现为「一次性 chat 后整体回调」，保证测试桩/降级实现无需改动即可兼容；
     * 真实 {@code DeepSeekLlmClient} 覆盖此方法实现真正的逐 token（SSE）转发。
     *
     * @param messages 对话消息
     * @param tools    工具 Schema（流式话术润色传空列表）
     * @param onToken  每个文本片段的消费者
     * @return 拼接后的完整文本（可能为空）
     */
    default String chatStream(List<LlmMessage> messages, List<Map<String, Object>> tools,
                              Consumer<String> onToken) {
        LlmResponse resp = chat(messages, tools);
        String content = resp == null ? null : resp.content();
        if (content != null && !content.isBlank()) {
            onToken.accept(content);
        }
        return content;
    }

    /** 是否已配置真实 API key（未配置则走降级）。 */
    boolean isConfigured();

    /**
     * LLM 回复。
     *
     * @param content      文本内容（可能为 null，当只有 toolCalls 时）
     * @param toolCalls    工具调用列表（可能为空）
     */
    record LlmResponse(String content, List<Map<String, Object>> toolCalls) {
    }
}
