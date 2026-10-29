package com.minsu.agent.llm;

import java.util.List;
import java.util.Map;

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
