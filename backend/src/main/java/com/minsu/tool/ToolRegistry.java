package com.minsu.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 工具注册表 —— 6 个工具的 Schema 清单（对齐《框架设计蓝图》第 6.2 节）。
 *
 * <p>这是「工具层先行」的核心：先把工具 Schema 写得模型能自己选对，再让 Agent 去用。
 * 每个工具的「边界句」是切开相邻工具混淆的关键，混淆矩阵验收就是针对这里的边界句调优。
 *
 * <p>6 工具按意图切分（不按数据库表切）：
 * <ul>
 *   <li>{@code search_rooms} 只读 —— 没有 roomId，在筛</li>
 *   <li>{@code quote_price} 只读 —— 有 roomId，没确认价</li>
 *   <li>{@code create_order} 写入 —— 有 roomId + 已确认价 + 明确要订</li>
 *   <li>{@code get_order} 只读 —— 查已有订单</li>
 *   <li>{@code cancel_order} 写入 —— 取消已有订单</li>
 *   <li>{@code ask_house_info} 只读(RAG) —— 自然语言问房源/政策</li>
 * </ul>
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public ToolRegistry() {
        registerSearchRooms();
        registerQuotePrice();
        registerCreateOrder();
        registerGetOrder();
        registerCancelOrder();
        registerAskHouseInfo();
    }

    public ToolDefinition get(String name) {
        return tools.get(name);
    }

    public List<ToolDefinition> all() {
        return List.copyOf(tools.values());
    }

    /** 所有工具序列化为 Function Calling 定义（喂 LLM）。 */
    public List<Map<String, Object>> allFunctionSpecs() {
        return tools.values().stream().map(ToolDefinition::toFunctionSpec).toList();
    }

    // ---------------------------------------------------------------- 6 工具

    private void registerSearchRooms() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("destination", strProp("目的地，如「莫干山」"));
        props.put("area", strProp("片区（可选），如庾村/劳岭仙潭/后坞/筏头/南路"));
        props.put("checkIn", strProp("入住日期（必填，格式 YYYY-MM-DD）"));
        props.put("checkOut", strProp("离店日期（必填，格式 YYYY-MM-DD）"));
        props.put("guests", intProp("入住人数（可选，默认 2）"));
        props.put("budgetPerNight", intProp("每晚预算上限（可选，单位元）"));
        props.put("relationship", strProp("出行关系（可选）：情侣/朋友/亲子/家庭", List.of("情侣", "朋友", "亲子", "家庭")));
        props.put("style", strProp("风格偏好（可选）：设计/原木/网红/竹海/溪流", List.of("设计", "原木", "网红", "竹海", "溪流")));

        Map<String, Object> params = schema(props, List.of("destination", "checkIn", "checkOut"));

        tools.put("search_rooms", new ToolDefinition(
                "search_rooms",
                "在某个目的地/片区按入住日期、预算、人数、风格等条件筛选候选房源列表。用户在「找房/筛选」阶段、还没有确定某套房间时使用。",
                "边界：当用户还没确定具体房型、在「筛」时用本工具；当用户已锁定某一套房（有 roomId）、要「看价格」时改用 quote_price。区别是「有没有 roomId」。",
                "前置条件：至少知道目的地、入住日期、离店日期。",
                params,
                "返回候选房源列表，每套含 roomId、定位、价格区间、适配理由、缺点；带 note 提示是否可直接进入报价。",
                false
        ));
    }

    private void registerQuotePrice() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("roomId", strProp("房源 ID（必填）"));
        props.put("checkIn", strProp("入住日期（必填，格式 YYYY-MM-DD）"));
        props.put("checkOut", strProp("离店日期（必填，格式 YYYY-MM-DD）"));
        props.put("guests", intProp("入住人数（可选，默认 2）"));

        Map<String, Object> params = schema(props, List.of("roomId", "checkIn", "checkOut"));

        tools.put("quote_price", new ToolDefinition(
                "quote_price",
                "对某一套已确定的房源（有 roomId）计算含全部费用的最终报价。用户在「锁定某套房、要确认价格」时使用。",
                "边界：当用户有 roomId、要确认价时用本工具；当用户还没确定房间、在「筛」时改用 search_rooms。区别是「有没有 roomId」。",
                "前置条件：已有 roomId（来自 search_rooms 结果）。",
                params,
                "返回含全部费用的总价明细（房费+清洁费+服务费等）；带 note='已含价，无需再调报价'。",
                false
        ));
    }

    private void registerCreateOrder() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("roomId", strProp("房源 ID（必填）"));
        props.put("checkIn", strProp("入住日期（必填，格式 YYYY-MM-DD）"));
        props.put("checkOut", strProp("离店日期（必填，格式 YYYY-MM-DD）"));
        props.put("guests", intProp("入住人数（必填）"));
        props.put("idempotencyKey", strProp("幂等键（必填）：同一次下单意图的唯一标识，防止重复下单"));

        Map<String, Object> params = schema(props, List.of("roomId", "checkIn", "checkOut", "guests", "idempotencyKey"));

        tools.put("create_order", new ToolDefinition(
                "create_order",
                "为已确认的房源创建预订订单（写入操作）。用户已确认价格、明确表达「要订」后使用。",
                "边界：当用户有 roomId + 已确认价 + 明确要订时用本工具；仅看价格用 quote_price；仅筛选用 search_rooms。",
                "前置条件：已调用 quote_price 确认价格，且用户明确同意下单。",
                params,
                "返回订单号 orderId 与订单状态；写入操作需用户二次确认。",
                true
        ));
    }

    private void registerGetOrder() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("orderId", strProp("订单号（必填）"));

        Map<String, Object> params = schema(props, List.of("orderId"));

        tools.put("get_order", new ToolDefinition(
                "get_order",
                "查询某一订单的当前状态与详情。用户问「我的订单怎么样了」时使用。",
                "边界：当用户要查已有订单时用本工具；当用户要取消订单时改用 cancel_order。区别是「查」还是「取消」。",
                "前置条件：已有 orderId。",
                params,
                "返回订单状态（待支付/已确认/已取消）与详情。",
                false
        ));
    }

    private void registerCancelOrder() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("orderId", strProp("订单号（必填）"));

        Map<String, Object> params = schema(props, List.of("orderId"));

        tools.put("cancel_order", new ToolDefinition(
                "cancel_order",
                "取消一个已有订单（写入操作）。用户明确要取消时使用。",
                "边界：当用户要取消订单时用本工具；仅查看订单状态用 get_order。区别是「取消」还是「查」。",
                "前置条件：已有 orderId，且订单可取消（未入住）。",
                params,
                "返回取消结果与是否产生取消费用；写入操作需用户二次确认。",
                true
        ));
    }

    private void registerAskHouseInfo() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("roomId", strProp("房源 ID（可选，若针对某套房源）"));
        props.put("question", strProp("自然语言问题（必填），如「能带狗吗/几点退房/能开发票吗」"));

        Map<String, Object> params = schema(props, List.of("question"));

        tools.put("ask_house_info", new ToolDefinition(
                "ask_house_info",
                "用自然语言回答关于房源/入住政策/周边餐饮交通的问题（走 RAG 检索）。用户问描述性、知识性问题时使用。",
                "边界：当用户问的是「这房怎么样/政策是什么」这类知识问答时用本工具；当用户要找房/比价时改用 search_rooms/quote_price。区别是「问知识」还是「做交易」。",
                "前置条件：无硬性前置，question 即可。",
                params,
                "返回检索增强后的自然语言回答。",
                false
        ));
    }

    // ---------------------------------------------------------------- 构建辅助

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> strProp(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", desc);
        return p;
    }

    private static Map<String, Object> strProp(String desc, List<String> enums) {
        Map<String, Object> p = strProp(desc);
        p.put("enum", enums);
        return p;
    }

    private static Map<String, Object> intProp(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        p.put("description", desc);
        return p;
    }
}
