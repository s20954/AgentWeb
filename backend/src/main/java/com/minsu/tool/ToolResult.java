package com.minsu.tool;

import java.util.Map;

/**
 * 工具执行返回 —— 对齐《框架设计蓝图》第 6.5 节「返回要瘦」。
 *
 * <p>只返回「下一步决策需要的」字段，附加 {@code note} 掐掉错误动作
 * （例如 quote_price 返回里带 note="已含价，无需再调报价"）。
 */
public record ToolResult(
        String toolName,
        boolean success,
        Map<String, Object> data,
        String note
) {

    public static ToolResult ok(String toolName, Map<String, Object> data) {
        return ok(toolName, data, null);
    }

    public static ToolResult ok(String toolName, Map<String, Object> data, String note) {
        return new ToolResult(toolName, true, data, note);
    }

    public static ToolResult fail(String toolName, String errorType, boolean retryable, String message) {
        Map<String, Object> err = Map.of(
                "errorType", errorType,
                "retryable", retryable,
                "message", message
        );
        return new ToolResult(toolName, false, err, null);
    }
}
