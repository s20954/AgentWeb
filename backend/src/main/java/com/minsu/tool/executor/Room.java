package com.minsu.tool.executor;

/**
 * 房源模型（工具层用）。对齐《框架设计蓝图》第 5.4 节：每候选含定位+价格+适配理由+一个真实缺点。
 */
public record Room(
        String roomId,
        String destination,
        String area,
        String title,
        String positioning,
        int basePrice,
        String style,
        String fitReason,
        String drawback
) {
}
