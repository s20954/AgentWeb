package com.minsu.tool.executor;

import java.util.LinkedHashMap;
import java.util.Map;

import com.minsu.tool.ToolResult;
import com.minsu.tool.error.ToolErrorType;
import com.minsu.tool.error.ToolException;

/**
 * pay_order —— 写入，模拟支付（待支付 → 已确认）。
 *
 * <p>本阶段为 mock 支付：无真实支付网关，点击支付即把订单从 PENDING_PAYMENT 转为 CONFIRMED，
 * 让「报价 → 下单 → 支付 → 已确认」全链路在演示中闭环。真实支付网关接入是后续阶段。
 * 幂等：重复支付已确认订单返回同一结果，不重复扣款语义。
 */
public class PayOrderExecutor extends AbstractToolExecutor {

    private final OrderStore orderStore;

    public PayOrderExecutor(OrderStore orderStore) {
        this.orderStore = orderStore;
    }

    @Override
    public String toolName() {
        return "pay_order";
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String orderId = reqStr(args, "orderId");

        OrderStore.Order order = orderStore.get(orderId);
        if (order == null) {
            throw new ToolException(ToolErrorType.BUSINESS_REJECT,
                    "订单 " + orderId + " 不存在，无法支付，请核对订单号（不重试，换方案）。");
        }
        if (ctx.userId() == null || !ctx.userId().equals(order.userId())) {
            throw new ToolException(ToolErrorType.BUSINESS_REJECT,
                    "无权操作订单 " + orderId + "，请确认是否为本人订单（不重试）。");
        }
        if (order.status() == OrderStore.Status.CANCELLED) {
            throw new ToolException(ToolErrorType.BUSINESS_REJECT,
                    "订单 " + orderId + " 已取消，无法支付，请重新下单（不重试）。");
        }

        OrderStore.Order paid = orderStore.pay(orderId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", paid.orderId());
        data.put("roomId", paid.roomId());
        data.put("total", paid.totalAmount());
        data.put("status", paid.status().name());
        data.put("paid", true);

        return ToolResult.ok(toolName(), data, "支付成功，订单已确认；可用 get_order 查最新状态");
    }
}
