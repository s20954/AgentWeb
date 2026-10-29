package com.minsu.tool;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.minsu.rag.RagService;
import com.minsu.tool.error.ToolException;
import com.minsu.tool.executor.AskHouseInfoExecutor;
import com.minsu.tool.executor.CancelOrderExecutor;
import com.minsu.tool.executor.CreateOrderExecutor;
import com.minsu.tool.executor.GetOrderExecutor;
import com.minsu.tool.executor.OrderStore;
import com.minsu.tool.executor.QuotePriceExecutor;
import com.minsu.tool.executor.RoomStore;
import com.minsu.tool.executor.SearchRoomsExecutor;
import com.minsu.tool.executor.ToolExecutor;

/**
 * 工具分发器 —— 把工具名路由到对应执行器，统一异常转错误三类型返回。
 *
 * <p>这是「Tool Calling 执行」的入口（对齐《框架设计蓝图》第 2 章后端职责②）。
 * Agent 层（子agent3）和 LLM 流式代理会调用这里执行工具。
 */
@Component
public class ToolDispatcher {

    private final Map<String, ToolExecutor> executors;
    private final ToolRegistry registry;

    /** 构造器注入（消除 ToolRegistry 双实例，执行器可注入 LLM client / DB）。 */
    public ToolDispatcher(ToolRegistry registry,
                          RoomStore roomStore,
                          OrderStore orderStore,
                          RagService ragService) {
        this.registry = registry;
        this.executors = Map.of(
                "search_rooms", new SearchRoomsExecutor(roomStore),
                "quote_price", new QuotePriceExecutor(roomStore),
                "create_order", new CreateOrderExecutor(roomStore, orderStore),
                "get_order", new GetOrderExecutor(orderStore),
                "cancel_order", new CancelOrderExecutor(orderStore),
                "ask_house_info", new AskHouseInfoExecutor(ragService, roomStore)
        );
    }

    public ToolRegistry registry() {
        return registry;
    }

    /**
     * 执行工具。userId 从会话上下文注入（模型不可填）。
     *
     * @param userId 会话用户 ID
     * @param toolName 工具名
     * @param args 模型解析出的参数
     */
    public ToolResult dispatch(String userId, String toolName, Map<String, Object> args) {
        ToolExecutor executor = executors.get(toolName);
        if (executor == null) {
            return ToolResult.fail(toolName, "PARAM_INVALID", true,
                    "未知工具 " + toolName + "，可用工具见 registry（retryable）。");
        }
        try {
            return executor.execute(new ToolExecutor.ToolContext(userId), args);
        } catch (ToolException e) {
            return ToolResult.fail(toolName,
                    e.getErrorType().name(),
                    e.getErrorType().isRetryable(),
                    e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail(toolName, "STATE_ERROR", false,
                    "工具执行异常：" + e.getMessage() + "（不可重试，请换方案）。");
        }
    }
}
