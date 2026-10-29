package com.minsu.agent.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 对话消息（对齐 OpenAI 兼容协议）。
 *
 * <p>字段约定：
 * <ul>
 *   <li>{@code role}：system / user / assistant / tool</li>
 *   <li>{@code content}：文本（assistant 带 tool_calls 时为 null）</li>
 *   <li>{@code toolCalls}：仅 assistant 的 tool_calls 列表</li>
 *   <li>{@code toolCallId}：仅 role=tool 的顶层 {@code tool_call_id}，用于关联对应的工具调用</li>
 * </ul>
 */
public record LlmMessage(String role, String content, List<Map<String, Object>> toolCalls, String toolCallId) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content, null, null);
    }

    /** 带工具调用的 assistant 消息（content 为 null）。 */
    public static LlmMessage assistantToolCalls(List<Map<String, Object>> toolCalls) {
        return new LlmMessage("assistant", null, toolCalls, null);
    }

    /** 工具结果（role=tool，tool_call_id 为顶层字段）。 */
    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage("tool", content, null, toolCallId);
    }
}
