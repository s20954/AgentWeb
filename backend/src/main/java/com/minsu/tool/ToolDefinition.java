package com.minsu.tool;

import java.util.List;
import java.util.Map;

/**
 * 单个工具 Schema —— 对齐《框架设计蓝图》第 6.3 节「六槽位」。
 *
 * <p>六槽位：做什么 / 何时调 / 何时不调改用谁 / 前置条件 / 参数格式 / 返回什么。
 * 其中「何时不调改用谁」即「边界句」，用客观判据切开相邻工具的混淆。
 *
 * <p>本对象最终会序列化为 OpenAI Function Calling 的 tools 定义喂给 LLM。
 *
 * @param name           工具名（全局唯一，如 search_rooms）
 * @param description    做什么 + 何时调（合并进 description，供 LLM 判断）
 * @param boundary       边界句：何时不调用本工具、改用谁、区别的客观判据
 * @param precondition   前置条件（缺了就会 STATE_ERROR）
 * @param parameters     JSON Schema 格式的参数定义
 * @param returns        返回什么（供 LLM 理解结果）
 * @param requiresConfirm 是否为写入操作、需要用户二次确认
 */
public record ToolDefinition(
        String name,
        String description,
        String boundary,
        String precondition,
        Map<String, Object> parameters,
        String returns,
        boolean requiresConfirm
) {

    /** 序列化为喂给 LLM 的 function 定义（OpenAI 风格）。 */
    public Map<String, Object> toFunctionSpec() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description + " " + boundary,
                        "parameters", parameters
                )
        );
    }
}
