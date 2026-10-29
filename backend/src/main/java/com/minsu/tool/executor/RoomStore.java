package com.minsu.tool.executor;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 房源数据仓库 —— mock 数据（对齐《框架设计蓝图》第 5.3 节莫干山 5 片区）。
 *
 * <p>本阶段为工具层 mock 数据，莫干山 5 片区 × 各 1-3 套。真实数据接入是后续阶段。
 * 后续若走 RAG/数据库，此接口保持不变，只换实现。
 */
@Component
public class RoomStore {

    /** 莫干山 5 片区 mock 房源。 */
    private static final List<Room> ROOMS = List.of(
            // 庾村（镇中心/性价比）
            new Room("yg-001", "莫干山", "庾村", "老街里·白墙小院", "镇中心步行可达，性价比高", 420, "原木", "离商业街近，吃饭方便", "临街，周末早上略吵"),
            new Room("yg-002", "莫干山", "庾村", "庾村·临溪客栈", "推窗见溪，闹中取静", 480, "溪流", "溪景房带露台", "房间偏小"),
            // 劳岭仙潭（竹海/设计/安静）
            new Room("ll-001", "莫干山", "劳岭仙潭", "竹海·玻璃屋", "落地窗看竹海，设计感强", 1280, "设计", "竹海景观+私汤", "价格偏高，旺季紧张"),
            new Room("ll-002", "莫干山", "劳岭仙潭", "仙潭·原木别院", "安静隐世，适合发呆", 980, "原木", "安静，周边徒步线路多", "离镇远，需自驾"),
            // 后坞（高海拔/视野好/路陡）
            new Room("hw-001", "莫干山", "后坞", "后坞·云顶民宿", "高海拔视野开阔", 880, "设计", "山顶视野，看云海", "最后一段路陡，新手慎行"),
            // 筏头（溪流/亲子）
            new Room("ft-001", "莫干山", "筏头", "筏头·溪谷亲子屋", "溪流边，适合亲子", 650, "亲子", "有儿童游乐区，近溪水", "旺季亲子客多，略喧闹"),
            // 南路（新开发/网红）
            new Room("nl-001", "莫干山", "南路", "南路·网红咖啡民宿", "新开发片区，出片率高", 720, "网红", "自带咖啡厅，适合拍照", "新片区配套还在完善")
    );

    public List<Room> search(String destination, String area, LocalDate checkIn, LocalDate checkOut,
                             Integer guests, Integer budgetPerNight, String relationship, String style) {
        return ROOMS.stream()
                .filter(r -> r.destination().equals(destination))
                .filter(r -> area == null || area.isBlank() || r.area().equals(area))
                .filter(r -> budgetPerNight == null || r.basePrice() <= budgetPerNight)
                .filter(r -> style == null || style.isBlank() || r.style().equals(style))
                .toList();
    }

    public Room byId(String roomId) {
        return ROOMS.stream().filter(r -> r.roomId().equals(roomId)).findFirst().orElse(null);
    }
}
