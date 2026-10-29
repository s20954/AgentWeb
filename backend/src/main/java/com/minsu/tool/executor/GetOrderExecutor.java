package com.minsu.tool.executor;

import java.util.LinkedHashMap;
import java.util.Map;

import com.minsu.tool.ToolResult;
import com.minsu.tool.error.ToolErrorType;
import com.minsu.tool.error.ToolException;

/**
 * get_order —— 只读，查询订单状态与详情。
 */
public class GetOrderExecutor extends AbstractToolExecutor {

    private final OrderStore orderStore;

    public GetOrderExecutor(OrderStore orderStore) {
        this.orderStore = orderStore;
    }

    @Override
    public String toolName() {
        return "get_order";
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String orderId = reqStr(args, "orderId");

        OrderStore.Order order = orderStore.get(orderId);
        if (order == null) {
            throw new ToolException(ToolErrorType.BUSINESS_REJECT,
                    "订单 " + orderId + " 不存在，无法查询，请核对订单号（不重试，换方案）。");
        }
        if (ctx.userId() == null || !ctx.userId().equals(order.userId())) {
            throw new ToolException(ToolErrorType.BUSINESS_REJECT,
                    "无权操作订单 " + orderId + "，请确认是否为本人订单（不重试）。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.orderId());
        data.put("roomId", order.roomId());
        data.put("checkIn", order.checkIn().toString());
        data.put("checkOut", order.checkOut().toString());
        data.put("guests", order.guests());
        data.put("total", order.totalAmount());
        data.put("status", order.status().name());

        return ToolResult.ok(toolName(), data, "如需取消，调用 cancel_order 并传 orderId");
    }
}
