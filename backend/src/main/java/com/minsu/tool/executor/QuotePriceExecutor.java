package com.minsu.tool.executor;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import com.minsu.tool.ToolResult;
import com.minsu.tool.error.ToolErrorType;
import com.minsu.tool.error.ToolException;

/**
 * quote_price —— 只读，对某套已确定房源（有 roomId）计算含全部费用的最终报价。
 */
public class QuotePriceExecutor extends AbstractToolExecutor {

    private final RoomStore roomStore;

    public QuotePriceExecutor(RoomStore roomStore) {
        this.roomStore = roomStore;
    }

    @Override
    public String toolName() {
        return "quote_price";
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String roomId = reqStr(args, "roomId");
        LocalDate checkIn = reqDate(args, "checkIn");
        LocalDate checkOut = reqDate(args, "checkOut");
        Integer guestsVal = optInt(args, "guests");
        int guests = guestsVal == null ? 2 : guestsVal;
        if (guests <= 0) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "入住人数 guests 必须为正整数，请修正后重试（retryable）。");
        }

        Room room = roomStore.byId(roomId);
        if (room == null) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "找不到房源 roomId=" + roomId + "，请先用 search_rooms 获取有效房源（retryable）。");
        }

        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights <= 0) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "离店日期必须晚于入住日期，当前入住=" + checkIn + " 离店=" + checkOut + "，请修正（retryable）。");
        }
        // 上限校验（2026-11-17）：极端日期致 nights 巨大，(int) 强转乘法溢出成负数
        if (nights > 30) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "单次预订最多 30 晚，当前 " + nights + " 晚超出范围，请修正（retryable）。");
        }

        long roomFeeL = (long) room.basePrice() * nights;
        long cleaningL = 80;            // 固定清洁费
        long serviceL = Math.round(roomFeeL * 0.10); // 服务费 10%
        long totalL = roomFeeL + cleaningL + serviceL;
        if (totalL > Integer.MAX_VALUE) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "金额超出可处理范围，请缩短入住天数（retryable）。");
        }
        int roomFee = (int) roomFeeL;
        int cleaning = (int) cleaningL;
        int service = (int) serviceL;
        int total = (int) totalL;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", roomId);
        data.put("title", room.title());
        data.put("nights", nights);
        data.put("guests", guests);
        Map<String, Integer> fee = new LinkedHashMap<>();
        fee.put("roomFee", roomFee);
        fee.put("cleaning", cleaning);
        fee.put("service", service);
        data.put("fee", fee);
        data.put("total", total);

        return ToolResult.ok(toolName(), data, "已含全部费用，无需再调报价；确认后可直接 create_order");
    }
}
