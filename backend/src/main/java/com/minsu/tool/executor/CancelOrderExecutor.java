package com.minsu.tool.executor;

import java.util.LinkedHashMap;
import java.util.Map;

import com.minsu.tool.ToolResult;
import com.minsu.tool.error.ToolErrorType;
import com.minsu.tool.error.ToolException;

/**
 * cancel_order —— 写入（需用户二次确认），取消已有订单。
 */
public class CancelOrderExecutor extends AbstractToolExecutor {

    private final OrderStore orderStore;

    public CancelOrderExecutor(OrderStore orderStore) {
        this.orderStore = orderStore;
    }

    @Override
    public String toolName() {
        return "cancel_order";
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String orderId = reqStr(args, "orderId");

        OrderStore.Order order = orderStore.get(orderId);
        if (order == null) {
            throw new ToolException(ToolErrorType.BUSINESS_REJECT,
                    "订单 " + orderId + " 不存在，无法取消，请核对订单号（不重试，换方案）。");
        }
        if (ctx.userId() == null || !ctx.userId().equals(order.userId())) {
            throw new ToolException(ToolErrorType.BUSINESS_REJECT,
                    "无权操作订单 " + orderId + "，请确认是否为本人订单（不重试）。");
        }
        if (order.status() == OrderStore.Status.CANCELLED) {
            throw new ToolException(ToolErrorType.BUSINESS_REJECT,
                    "订单 " + orderId + " 已取消，无需重复操作（不重试）。");
        }

        OrderStore.Order cancelled = orderStore.cancel(orderId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", cancelled.orderId());
        data.put("status", cancelled.status().name());
        data.put("refund", cancelled.totalAmount()); // 简化：全额退

        return ToolResult.ok(toolName(), data, "已取消，全额退款");
    }
}
