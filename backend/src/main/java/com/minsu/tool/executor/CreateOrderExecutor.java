package com.minsu.tool.executor;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import com.minsu.tool.ToolResult;
import com.minsu.tool.error.ToolErrorType;
import com.minsu.tool.error.ToolException;

/**
 * create_order —— 写入（需用户二次确认），幂等。
 *
 * <p>对齐《框架设计蓝图》第 6.5 节：幂等（idempotencyKey）+ 鉴权从上下文取（userId 不在参数里）。
 */
public class CreateOrderExecutor extends AbstractToolExecutor {

    private final RoomStore roomStore;
    private final OrderStore orderStore;

    public CreateOrderExecutor(RoomStore roomStore, OrderStore orderStore) {
        this.roomStore = roomStore;
        this.orderStore = orderStore;
    }

    @Override
    public String toolName() {
        return "create_order";
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String roomId = reqStr(args, "roomId");
        LocalDate checkIn = reqDate(args, "checkIn");
        LocalDate checkOut = reqDate(args, "checkOut");
        Integer guestsVal = optInt(args, "guests");
        String idempotencyKey = reqStr(args, "idempotencyKey");

        if (guestsVal == null || guestsVal <= 0) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "入住人数 guests 必须为正整数，请补全后重试（retryable）。");
        }

        // 鉴权从上下文取，不做成模型可填参数
        String userId = ctx.userId();
        if (userId == null || userId.isBlank()) {
            throw new ToolException(ToolErrorType.STATE_ERROR,
                    "会话未识别到用户身份，请先完成登录/会话初始化（不可重试）。");
        }

        Room room = roomStore.byId(roomId);
        if (room == null) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "找不到房源 roomId=" + roomId + "，请先用 search_rooms 获取（retryable）。");
        }

        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights <= 0) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "离店日期必须晚于入住日期，请修正（retryable）。");
        }
        // 上限校验（2026-11-17）：极端日期（如离店 9999 年）会让 nights 达数百万，
        // 强转 int 后乘法溢出成负数、写入负金额订单。限制单次最多 30 晚。
        if (nights > 30) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "单次预订最多 30 晚，当前 " + nights + " 晚超出范围，请修正（retryable）。");
        }

        // 计算总价（与 quote_price 一致）：先按 long 计算再收窄，杜绝 int 溢出
        long roomFeeL = (long) room.basePrice() * nights;
        long cleaningL = 80;
        long serviceL = Math.round(roomFeeL * 0.10);
        long totalL = roomFeeL + cleaningL + serviceL;
        if (totalL > Integer.MAX_VALUE) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "金额超出可处理范围，请缩短入住天数（retryable）。");
        }
        int total = (int) totalL;

        // 幂等：同 idempotencyKey 返回同一订单
        OrderStore.Order order = orderStore.create(userId, roomId, checkIn, checkOut, guestsVal, total, idempotencyKey);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.orderId());
        data.put("roomId", order.roomId());
        data.put("total", order.totalAmount());
        data.put("status", order.status().name());
        data.put("idempotencyKey", idempotencyKey);

        return ToolResult.ok(toolName(), data, "订单已创建，待支付；可用 get_order 查状态");
    }
}
