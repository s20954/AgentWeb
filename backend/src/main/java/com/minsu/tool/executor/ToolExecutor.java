package com.minsu.tool.executor;

import java.util.Map;

import com.minsu.tool.ToolResult;

/**
 * 工具执行器接口。每个工具一个执行器。
 *
 * <p>对齐《框架设计蓝图》第 6.5 节工程铁律：
 * <ul>
 *   <li>鉴权：{@code userId} 从 {@link ToolContext}（会话上下文）取，不做成模型可填参数。</li>
 *   <li>幂等：写操作接受 idempotencyKey。</li>
 *   <li>返回要瘦：只返回下一步决策需要的。</li>
 * </ul>
 */
public interface ToolExecutor {

    /** 本执行器负责的工具名。 */
    String toolName();

    /** 执行工具。args 为模型解析出的参数（不含 userId，userId 从 ctx 取）。 */
    ToolResult execute(ToolContext ctx, Map<String, Object> args);

    /**
     * 工具上下文：从会话上下文注入的鉴权信息，模型不可见、不可填。
     *
     * @param userId 当前会话用户 ID（从请求上下文取）
     */
    record ToolContext(String userId) {
    }
}
