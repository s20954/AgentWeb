package com.minsu.agent.step;

import java.util.Map;

/**
 * 思考链事件 —— Agent 每走一步推送给前端的可解释事件。
 *
 * <p>SSE 流式输出里，思考链事件和文本 token 混合推送：
 * 前端据此渲染「思考链时间线」+「打字机文本」。
 *
 * @param type    事件类型（七步之一）
 * @param message 该步骤的说明文案
 * @param payload 附带的结构化数据（如缺口列表、候选房源）
 */
public record StepEvent(StepEventType type, String message, Map<String, Object> payload) {

    public static StepEvent of(StepEventType type, String message) {
        return new StepEvent(type, message, null);
    }

    public static StepEvent of(StepEventType type, String message, Map<String, Object> payload) {
        return new StepEvent(type, message, payload);
    }
}
