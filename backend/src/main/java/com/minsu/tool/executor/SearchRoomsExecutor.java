package com.minsu.tool.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.minsu.tool.ToolResult;
import com.minsu.tool.error.ToolErrorType;
import com.minsu.tool.error.ToolException;

/**
 * search_rooms —— 只读，按条件筛选候选房源（没有 roomId，在筛）。
 */
public class SearchRoomsExecutor extends AbstractToolExecutor {

    private final RoomStore roomStore;

    public SearchRoomsExecutor(RoomStore roomStore) {
        this.roomStore = roomStore;
    }

    @Override
    public String toolName() {
        return "search_rooms";
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String destination = reqStr(args, "destination");
        java.time.LocalDate checkIn = reqDate(args, "checkIn");
        java.time.LocalDate checkOut = reqDate(args, "checkOut");
        String area = args.get("area") == null ? null : args.get("area").toString();
        Integer guests = optInt(args, "guests");
        Integer budget = optInt(args, "budgetPerNight");
        String relationship = args.get("relationship") == null ? null : args.get("relationship").toString();
        String style = args.get("style") == null ? null : args.get("style").toString();

        // 日期顺序校验（与 quote_price / create_order 口径对齐）
        if (!checkOut.isAfter(checkIn)) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "离店日期必须晚于入住日期，当前入住=" + checkIn + " 离店=" + checkOut + "，请修正（retryable）。");
        }

        List<Room> rooms = roomStore.search(destination, area, checkIn,
                checkOut, guests, budget, relationship, style);

        List<Map<String, Object>> list = new ArrayList<>();
        for (Room r : rooms) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roomId", r.roomId());
            m.put("area", r.area());
            m.put("title", r.title());
            m.put("positioning", r.positioning());
            m.put("basePrice", r.basePrice());
            m.put("style", r.style());
            m.put("fitReason", r.fitReason());
            m.put("drawback", r.drawback());
            m.put("cover", r.cover());
            list.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", list.size());
        data.put("rooms", list);

        String note = list.isEmpty()
                ? "无匹配房源，建议放宽预算或片区后重搜"
                : "如需看某一套的最终报价，调用 quote_price 并传对应 roomId";

        return ToolResult.ok(toolName(), data, note);
    }
}
