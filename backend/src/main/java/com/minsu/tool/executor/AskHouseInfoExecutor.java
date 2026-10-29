package com.minsu.tool.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.minsu.rag.RagService;
import com.minsu.tool.ToolResult;

/**
 * ask_house_info —— 只读(RAG)，自然语言问答。
 *
 * <p>主路径走 {@link RagService} 纯内存余弦检索，把命中的知识片段拼成回答；
 * 命中不足（分数过低）时回退到规则匹配兜底。返回里带命中文档的 id/category/score，
 * 供前端展示检索来源、增强可解释性。
 */
public class AskHouseInfoExecutor extends AbstractToolExecutor {

    /** 检索兜底阈值：最高分低于此值视为无有效命中，走规则兜底。 */
    private static final double FALLBACK_THRESHOLD = 0.05;

    private final RagService ragService;
    private final RoomStore roomStore;

    public AskHouseInfoExecutor(RagService ragService, RoomStore roomStore) {
        this.ragService = ragService;
        this.roomStore = roomStore;
    }

    @Override
    public String toolName() {
        return "ask_house_info";
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String question = reqStr(args, "question");
        String roomId = args.get("roomId") == null ? null : args.get("roomId").toString();

        List<RagService.Hit> hits = ragService.search(question, 3);
        boolean fallback = hits.isEmpty() || hits.get(0).score() < FALLBACK_THRESHOLD;

        String answer = fallback ? ruleAnswer(roomId, question) : buildRagAnswer(roomId, question, hits);

        Map<String, Object> data = new LinkedHashMap<>();
        if (roomId != null) {
            data.put("roomId", roomId);
        }
        data.put("question", question);
        data.put("answer", answer);
        data.put("source", "rag");

        // 检索来源（含兜底情况下的空命中），供前端解释
        List<Map<String, Object>> hitList = new ArrayList<>();
        for (RagService.Hit h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.id());
            m.put("category", h.category());
            m.put("score", Math.round(h.score() * 1000) / 1000.0);
            hitList.add(m);
        }
        data.put("hits", hitList);
        data.put("fallback", fallback);

        return ToolResult.ok(toolName(), data,
                fallback ? "未检索到足够相关的知识，已用规则兜底回答；如需预订走 search_rooms/quote_price"
                        : "基于 RAG 检索的知识回答；如需预订走 search_rooms/quote_price");
    }

    /** 组织 RAG 回答：若带 roomId 先拼房源信息，再拼命中的知识片段。 */
    private String buildRagAnswer(String roomId, String question, List<RagService.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        if (roomId != null) {
            Room room = roomStore.byId(roomId);
            if (room != null) {
                sb.append("关于「").append(room.title()).append("」（").append(room.area()).append(" · ")
                  .append(room.style()).append("）：").append(room.positioning())
                  .append("；").append(room.fitReason())
                  .append("。需注意：").append(room.drawback()).append("。\n\n");
            }
        }
        for (int i = 0; i < hits.size(); i++) {
            RagService.Hit h = hits.get(i);
            sb.append("【").append(categoryLabel(h.category())).append("】").append(h.content());
            if (i < hits.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String categoryLabel(String category) {
        return switch (category) {
            case "片区介绍" -> "片区";
            case "政策FAQ" -> "政策";
            case "房源特色" -> "房源";
            case "游玩攻略" -> "攻略";
            default -> category;
        };
    }

    /** 规则兜底（无检索命中时）。 */
    private String ruleAnswer(String roomId, String question) {
        String q = question.toLowerCase();
        if (q.contains("狗") || q.contains("宠物") || q.contains("pet")) {
            return "多数民宿允许小型宠物，但需提前告知并可能收取清洁费，请以具体房源政策为准。";
        }
        if (q.contains("退房") || q.contains("checkout") || q.contains("check-out")) {
            return "标准退房时间为中午 12:00 前，部分房源可协商延迟。";
        }
        if (q.contains("发票") || q.contains("开票")) {
            return "支持开具增值税普通发票，请在入住时或离店后向前台提供抬头信息。";
        }
        if (q.contains("吃") || q.contains("餐") || q.contains("饭")) {
            return roomId == null
                    ? "莫干山各片区均有本地农家菜，庾村一带餐饮最集中。"
                    : "该房源周边有本地农家菜，可步行或短途车程到达。";
        }
        return "暂时无法给出确定回答，建议查看房源详情或联系房东确认。";
    }
}
