package com.minsu.tool;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.minsu.agent.llm.DeepSeekLlmClient;
import com.minsu.agent.llm.LlmClient;
import com.minsu.agent.llm.LlmMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混淆矩阵首轮通过率验收 —— 工具层「AI 可控」硬指标（目标 ≥90%）。
 *
 * <p>验证的是：LLM 面对一批「用户自然语言意图」时，能否在<b>首轮 function calling</b>
 * 就选对工具、填对关键参数。这是工具 Schema 六槽位 + 边界句写得是否「模型能自己选对」的终极检验。
 *
 * <p>判定口径（每条用例）：
 * <ul>
 *   <li><b>工具选对</b>：模型返回的 {@code toolCalls[0].function.name} 等于期望工具名；</li>
 *   <li><b>参数达标</b>：期望的关键参数存在且非空（如 search_rooms 的 destination）。</li>
 * </ul>
 * 只有「工具选对 + 参数达标」才算该条通过。
 *
 * <p>执行方式：需要 {@code LLM_API_KEY} 环境变量（真实调 DeepSeek）。
 * 无 key 时本测试自动跳过（{@code @EnabledIfEnvironmentVariable}），
 * 并在控制台打印「请设置 LLM_API_KEY 后重跑」。
 *
 * <p>跑法（在能读到 key 的终端）：
 * <pre>
 *   cd backend
 *   mvn -Dtest=ConfusionMatrixTest test
 * </pre>
 * 结果会在 stdout 打印完整混淆矩阵（期望 vs 实际工具）+ 首轮通过率。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class ConfusionMatrixTest {

    @Autowired
    DeepSeekLlmClient llmClient;

    @Autowired
    ToolRegistry registry;

    /** 一条验收用例：自然语言意图 → 期望工具名 + 期望关键参数。 */
    record Case(String intent, String expectedTool, String expectedKey, String expectedValue) {
    }

    /** 验收用例集：覆盖 6 工具 + 相邻工具边界混淆。 */
    private static final List<Case> CASES = List.of(
            // ---- search_rooms（找房/筛选）----
            new Case("我想下周末去莫干山住两晚，预算每晚500以内，帮我找找房", "search_rooms", "destination", "莫干山"),
            new Case("帮我看看庾村有哪些性价比高的民宿", "search_rooms", "area", "庾村"),
            new Case("带孩子去莫干山玩，想找个适合亲子的民宿", "search_rooms", "relationship", "亲子"),
            // ---- quote_price（锁定某套，看价）----
            new Case("yg-001 这套房住两晚要多少钱", "quote_price", "roomId", "yg-001"),
            new Case("帮我把刚才那套竹海玻璃屋报个价", "quote_price", "roomId", "ll-001"),
            // ---- create_order（明确要订）----
            new Case("就订 yg-001 这套，11月1日入住，住到3号，两个人", "create_order", "roomId", "yg-001"),
            new Case("帮我下单，房号 ll-001，1号到3号", "create_order", "roomId", "ll-001"),
            // ---- get_order（查订单）----
            new Case("我昨天下的那个订单现在什么状态了", "get_order", "orderId", null),
            new Case("查一下订单 MS123456 的进度", "get_order", "orderId", "MS123456"),
            // ---- cancel_order（取消订单）----
            new Case("帮我把订单 MS123456 取消了", "cancel_order", "orderId", "MS123456"),
            new Case("我不去了，把刚才那单退了吧", "cancel_order", "orderId", null),
            // ---- ask_house_info（知识问答）----
            new Case("莫干山的民宿能带宠物吗", "ask_house_info", "question", null),
            new Case("庾村附近有什么好吃的农家菜", "ask_house_info", "question", null),
            // ---- 边界混淆（最容易选错的相邻工具）----
            new Case("yg-001 这套怎么样，适合情侣住吗", "ask_house_info", "question", null), // 问知识，非报价
            new Case("帮我把 yg-001 的价格算一下", "quote_price", "roomId", "yg-001"),      // 看价，非搜索
            new Case("我想订房，先帮我看看有哪些选择", "search_rooms", "destination", null)   // 还没锁定，非下单
    );

    @Test
    void firstRoundToolSelectionPassRate() {
        assertTrue(llmClient.isConfigured(), "LLM_API_KEY 未配置（本测试应被 @EnabledIf 跳过，走到这里说明配置异常）");

        List<Map<String, Object>> specs = registry.allFunctionSpecs();
        int total = CASES.size();
        int pass = 0;
        StringBuilder matrix = new StringBuilder();

        matrix.append("\n==================== 混淆矩阵验收 ====================\n");
        matrix.append(String.format("%-6s | %-16s | %-16s | %s\n", "结果", "期望工具", "实际工具", "意图"));
        matrix.append("------------------------------------------------------\n");

        for (Case c : CASES) {
            LlmClient.LlmResponse resp = llmClient.chat(
                    List.of(
                            LlmMessage.system("你是民宿预约顾问。根据用户意图，选择最合适的工具并填写参数。只调用一个工具。"),
                            LlmMessage.user(c.intent())
                    ),
                    specs
            );

            String actualTool = null;
            Map<String, Object> actualArgs = null;
            if (resp != null && resp.toolCalls() != null && !resp.toolCalls().isEmpty()) {
                Map<String, Object> call = resp.toolCalls().get(0);
                Object fn = call.get("function");
                if (fn instanceof Map<?, ?> fnMap) {
                    actualTool = String.valueOf(fnMap.get("name"));
                    Object argsRaw = fnMap.get("arguments");
                    actualArgs = parseArgs(argsRaw == null ? "{}" : argsRaw.toString());
                }
            }

            boolean toolOk = c.expectedTool().equals(actualTool);
            boolean paramOk = paramOk(actualArgs, c.expectedKey(), c.expectedValue());
            boolean ok = toolOk && paramOk;
            if (ok) pass++;

            matrix.append(String.format("%-6s | %-16s | %-16s | %s\n",
                    ok ? "✓" : "✗",
                    c.expectedTool(),
                    actualTool == null ? "(无)" : actualTool,
                    c.intent()));
            if (!ok && actualArgs != null) {
                matrix.append(String.format("        参数=%s\n", actualArgs));
            }
        }

        double rate = total == 0 ? 0 : (pass * 100.0 / total);
        matrix.append("------------------------------------------------------\n");
        matrix.append(String.format("首轮通过率：%d/%d = %.1f%%（目标 ≥90%%）\n", pass, total, rate));
        matrix.append("======================================================\n");

        System.out.println(matrix);
        System.out.println("DEBUG_EXPECTED_TOOL_COUNT=" + CASES.stream().map(Case::expectedTool).distinct().count());

        // 硬验收：≥90%
        assertTrue(rate >= 90.0,
                String.format("混淆矩阵首轮通过率 %.1f%% 未达 90%% 目标", rate));
    }

    /** 解析工具调用参数（arguments 是 JSON 字符串）。 */
    private Map<String, Object> parseArgs(String argsJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> m = om.readValue(argsJson, Map.class);
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 参数是否达标：关键参数存在；若指定了期望值则需一致。 */
    private boolean paramOk(Map<String, Object> args, String key, String expectedValue) {
        if (args == null || key == null) return true;
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) return false;
        if (expectedValue != null) {
            return String.valueOf(v).contains(expectedValue);
        }
        return true;
    }
}
