package com.minsu.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.minsu.agent.llm.LlmClient;
import com.minsu.agent.step.StepEvent;
import com.minsu.agent.step.StepEventType;
import com.minsu.rag.KbStore;
import com.minsu.rag.RagService;
import com.minsu.tool.ToolDispatcher;
import com.minsu.tool.ToolRegistry;
import com.minsu.tool.executor.OrderStore;
import com.minsu.tool.executor.RoomStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 层七步收敛测试 —— 对齐《框架设计蓝图》第 5 章验收标准。
 */
class AgentServiceTest {

    private AgentService agentService;
    private List<StepEvent> steps;
    private List<String> texts;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry();
        RagService ragService = new RagService(new KbStore(new com.minsu.rag.KbRepository(
                System.getProperty("java.io.tmpdir") + "/minsu-kb-test-" + System.nanoTime() + ".db")));
        ToolDispatcher dispatcher = new ToolDispatcher(registry, new RoomStore(), new OrderStore(), ragService);
        // 用未配置的 LLM client（本轮骨架确定性逻辑不依赖 LLM，仅保留注入）
        LlmClient noopLlm = new LlmClient() {
            @Override
            public LlmResponse chat(List<com.minsu.agent.llm.LlmMessage> messages, List<Map<String, Object>> tools) {
                return new LlmResponse("", List.of());
            }

            @Override
            public boolean isConfigured() {
                return false;
            }
        };
        agentService = new AgentService(dispatcher, registry, noopLlm, new ConversationMemory(), ragService,
                new RoomStore());
        steps = new ArrayList<>();
        texts = new ArrayList<>();
    }

    private AgentService.EventSink sink() {
        return new AgentService.EventSink() {
            @Override
            public void step(StepEvent event) {
                steps.add(event);
            }

            @Override
            public void text(String text) {
                texts.add(text);
            }
        };
    }

    // ---------------------------------------------------------------- 缺口识别

    @Test
    void incompleteInputAsksQuestions() {
        // 只有目的地，缺日期/预算/关系 → 应走「带着方案问」，不筛选
        String answer = agentService.run("u1", "我想去莫干山", sink());

        // 应有 EXTRACTING + GAP_FOUND + ASKING 三步，无 FILTERING
        List<StepEventType> types = steps.stream().map(s -> s.type()).toList();
        assertTrue(types.contains(StepEventType.EXTRACTING));
        assertTrue(types.contains(StepEventType.GAP_FOUND));
        assertTrue(types.contains(StepEventType.ASKING));
        assertFalse(types.contains(StepEventType.FILTERING), "缺信息时不应筛选");

        assertFalse(texts.isEmpty());
        String full = String.join("", texts);
        assertTrue(full.contains("日期"));
        assertTrue(full.contains("预算"));
        assertTrue(full.contains("出行关系"));
    }

    // ---------------------------------------------------------------- 完整收敛

    @Test
    void completeInputRunsFullSevenSteps() {
        String answer = agentService.run("u1",
                "莫干山，11月1日到3日，预算每晚1500，两个人（情侣），喜欢设计风格", sink());

        List<StepEventType> types = steps.stream().map(s -> s.type()).toList();
        // 完整信息 → 走全七步
        assertTrue(types.contains(StepEventType.EXTRACTING));
        assertTrue(types.contains(StepEventType.GAP_FOUND));
        assertFalse(types.contains(StepEventType.ASKING), "信息完整时不应问");
        assertTrue(types.contains(StepEventType.LOADING_KNOWLEDGE));
        assertTrue(types.contains(StepEventType.FILTERING));
        assertTrue(types.contains(StepEventType.PACKAGING));
        assertTrue(types.contains(StepEventType.DECIDING));

        // 最终答案满足硬约束：有主推 + 缺点 + 下一步
        String full = String.join("", texts);
        assertTrue(full.contains("主推"), "应有唯一主推");
        assertTrue(full.contains("缺点"), "每候选应有真实缺点");
        assertTrue(full.contains("下一步"), "应给具体下一步动作");
        assertFalse(full.contains("你觉得呢"), "禁止开放式提问收尾");
    }

    // ---------------------------------------------------------------- 硬约束

    @Test
    void budgetFilterRespectsBudget() {
        // 预算很低 → 只能命中低价房源
        String answer = agentService.run("u1",
                "莫干山，11月1日到3日，预算每晚500，情侣", sink());
        // 预算 500 → 只命中 420/480 两套
        assertTrue(answer.contains("候选"), "应有候选");
    }

    @Test
    void noMatchGivesFallback() {
        // 预算极低 → 无匹配
        String answer = agentService.run("u1",
                "莫干山，11月1日到3日，预算每晚10，情侣", sink());
        assertTrue(answer.contains("没有找到") || answer.contains("放宽"), "无匹配应有兜底");
    }

    // ---------------------------------------------------------------- 实体抽取

    @Test
    void extractsRelationshipFamily() {
        String answer = agentService.run("u1",
                "莫干山，11月1日到3日，预算每晚800，带孩子去亲子游", sink());
        String full = String.join("", texts);
        // 亲子关系被识别，信息完整走完七步
        assertTrue(full.contains("主推") || full.contains("候选"), "应完成推荐");
    }

    // ---------------------------------------------------------------- BUG-7 跨月日期

    @Test
    void crossMonthDateDoesNotInvert() {
        // 「11月28日到12月2日」跨月，checkOut 应为 12 月而非 11 月（倒挂）
        String answer = agentService.run("crossMonth", 
                "莫干山，11月28日到12月2日，预算每晚1500，情侣", sink());
        String full = String.join("", texts);
        // 日期不倒挂 → 走完筛选并给出候选（预算 1500 能命中 ll-001 1280 等）
        assertTrue(full.contains("主推") || full.contains("候选"), "跨月日期应能正确筛出房源，不应误报无房");
        assertFalse(full.contains("没有找到"), "跨月日期不应被误判为倒挂而报无房源");
    }

    // ---------------------------------------------------------------- BUG-8 多轮收敛

    @Test
    void multiTurnAccumulatesEntities() {
        // T1：只给目的地 → 问日期/预算/关系
        String t1 = agentService.run("multiTurn", "我想去莫干山玩", sink());
        assertTrue(t1.contains("日期") && t1.contains("预算"), "首轮应问缺失信息");

        steps.clear();
        texts.clear();

        // T2：补充日期 → 仍缺预算/关系，但不重问日期
        String t2 = agentService.run("multiTurn", "11月1日到3日", sink());
        assertTrue(t2.contains("预算") && t2.contains("出行关系"), "二轮应继续问预算/关系");
        assertFalse(t2.contains("日期"), "二轮不应重问已提供的日期");

        steps.clear();
        texts.clear();

        // T3：补齐预算和关系 → 走完收敛
        String t3 = agentService.run("multiTurn", "预算每晚800，情侣", sink());
        assertTrue(t3.contains("主推") || t3.contains("候选"), "三轮补齐后应完成推荐");
    }

    // ---------------------------------------------------------------- 低危-4 guests 传递

    @Test
    void extractsAndPassesGuests() {
        // 「4个人」应被抽取为人数，且完整信息走完七步
        String answer = agentService.run("guests1",
                "莫干山，11月1日到3日，预算每晚1500，4个人，家庭", sink());
        assertTrue(answer.contains("主推") || answer.contains("候选"), "含人数信息应正常推荐");
    }

    // ---------------------------------------------------------------- 新修复：单日期+时长 / 裸数字预算

    @Test
    void singleDateWithNightsDerivesCheckOut() {
        // 「11月1日就一天」应推导 checkIn=11-01, checkOut=11-02，不误报缺日期
        String answer = agentService.run("single1", "莫干山，11月1日就一天，预算800，朋友", sink());
        String full = String.join("", texts);
        assertTrue(full.contains("主推") || full.contains("候选"),
                "「就一天」应能推导离店日期并完成推荐，实际回复：" + full);
    }

    @Test
    void bareNumberAsBudget() {
        // 无「预算/每晚」关键词，裸数字 800 应被识别为预算
        String answer = agentService.run("bare1", "莫干山，11月1日到3日，800，朋友", sink());
        String full = String.join("", texts);
        assertTrue(full.contains("主推") || full.contains("候选"),
                "裸数字 800 应被识别为预算并完成推荐，实际回复：" + full);
    }

    @Test
    void multiTurnSupplementSingleDateAndBudget() {
        // T1：只给目的地 → 问日期/预算/关系
        String t1 = agentService.run("suppl", "我准备去莫干山，去俩人", sink());
        assertTrue(t1.contains("日期") && t1.contains("预算"), "首轮应问缺失信息");

        steps.clear();
        texts.clear();

        // T2：补「11月1日就一天 + 800 + 朋友」→ 应走完收敛，不再追问
        String t2 = agentService.run("suppl", "11月1日就一天，800，朋友", sink());
        assertTrue(t2.contains("主推") || t2.contains("候选"),
                "多轮补齐（就一天+裸预算+朋友）应完成推荐，实际回复：" + t2);
        assertFalse(t2.contains("还差"), "补齐后不应再追问，实际回复：" + t2);
    }

    // ---------------------------------------------------------------- E2E 修复：交易意图不得被规则轨道锁死

    /**
     * 可配置的 LLM 桩：tools 为空视为润色调用（返回空内容回退模板）；
     * tools 非空为工具循环调用：第一次返回 toolCalls，之后返回最终文本。
     */
    private LlmClient scriptedToolLlm(String toolName, String argumentsJson, String finalAnswer) {
        return new LlmClient() {
            boolean toolResultFed = false;

            @Override
            public LlmResponse chat(List<com.minsu.agent.llm.LlmMessage> messages, List<Map<String, Object>> tools) {
                if (tools == null || tools.isEmpty()) {
                    return new LlmResponse("", List.of());
                }
                if (!toolResultFed) {
                    toolResultFed = true;
                    Map<String, Object> fn = Map.of("name", toolName, "arguments", argumentsJson);
                    Map<String, Object> call = Map.of("id", "call-1", "function", fn);
                    return new LlmResponse("", List.of(call));
                }
                return new LlmResponse(finalAnswer, List.of());
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
    }

    private AgentService agentWith(LlmClient llm) {
        ToolRegistry registry = new ToolRegistry();
        RagService ragService = new RagService(new KbStore(new com.minsu.rag.KbRepository(
                System.getProperty("java.io.tmpdir") + "/minsu-kb-test-" + System.nanoTime() + ".db")));
        ToolDispatcher dispatcher = new ToolDispatcher(registry, new RoomStore(), new OrderStore(), ragService);
        return new AgentService(dispatcher, registry, llm, new ConversationMemory(), ragService, new RoomStore());
    }

    @Test
    void quoteIntentAfterConvergenceRoutesToToolLoop() {
        // 回归背景：三要素齐全后 isSoftDemand 恒 false，问价被锁死在规则轨道重走推荐（E2E 第3轮实测 bug）
        AgentService svc = agentWith(scriptedToolLlm("quote_price",
                "{\"roomId\":\"yg-001\",\"checkIn\":\"2026-11-21\",\"checkOut\":\"2026-11-22\"}",
                "yg-001 两晚总价 930 元（含清洁费）。"));
        // 多轮补齐三要素（规则轨道完成推荐）
        svc.run("tx1", "我想去莫干山", sink());
        svc.run("tx1", "11月21日到22日", sink());
        svc.run("tx1", "预算600，情侣", sink());

        steps.clear();
        texts.clear();

        // T4：三要素已齐全，明确问价 → 必须进 LLM 工具轨道调 quote_price，不得重走推荐
        String answer = svc.run("tx1", "yg-001 这套住两晚要多少钱", sink());

        List<StepEventType> types = steps.stream().map(StepEvent::type).toList();
        assertTrue(types.contains(StepEventType.TOOL_CALLING), "问价应走工具调用轨道");
        assertTrue(steps.stream().anyMatch(s -> s.message() != null && s.message().contains("quote_price")),
                "应调用 quote_price，实际步骤：" + steps.stream().map(StepEvent::message).toList());
        assertTrue(answer.contains("930"), "应基于真实工具结果回答，实际：" + answer);
        assertFalse(types.contains(StepEventType.DECIDING), "问价不应重走推荐包装");
    }

    @Test
    void cancelIntentRoutesToToolLoop() {
        // 「把订单退了」是交易意图：即使当前会话没有已识别三要素，也应交给 LLM 工具轨道处理
        AgentService svc = agentWith(scriptedToolLlm("cancel_order",
                "{\"orderId\":\"MS123456\"}",
                "订单 MS123456 已取消。"));
        String answer = svc.run("tx2", "帮我把订单 MS123456 退了", sink());

        List<StepEventType> types = steps.stream().map(StepEvent::type).toList();
        assertTrue(types.contains(StepEventType.TOOL_CALLING), "取消订单应走工具调用轨道");
        assertTrue(steps.stream().anyMatch(s -> s.message() != null && s.message().contains("cancel_order")),
                "应调用 cancel_order");
        assertTrue(answer.contains("已取消"), "应基于真实工具结果回答，实际：" + answer);
        assertFalse(types.contains(StepEventType.ASKING), "取消意图不应触发缺口追问");
    }

    @Test
    void searchIntentDoesNotTriggerToolLoop() {
        // 反向回归：普通推荐诉求（无交易词）仍应走规则轨道七步，不被误路由
        String answer = agentService.run("tx3",
                "莫干山，11月1日到3日，预算每晚1500，两个人（情侣），喜欢设计风格", sink());
        List<StepEventType> types = steps.stream().map(StepEvent::type).toList();
        assertTrue(types.contains(StepEventType.FILTERING), "普通找房应走规则筛选轨道");
        assertTrue(answer.contains("主推"), "应完成推荐");
    }

    // ---------------------------------------------------------------- 目的地泛化（2026-11-17 成都 bug）

    @Test
    void unsupportedDestinationAnswersHonestly() {
        // 识别「西安」，但数据层无西安房源 → 诚实告知，不瞎编、不硬套已有城市
        String answer = agentService.run("cd1",
                "我要去西安游玩两天，和朋友俩人，预算每晚500", sink());

        List<StepEventType> types = steps.stream().map(StepEvent::type).toList();
        // 应识别到目的地切换，且不进入规则筛选（数据层不覆盖西安）
        assertFalse(types.contains(StepEventType.FILTERING), "西安无数据不应进入筛选");
        assertTrue(answer.contains("西安"), "回复应明确提及目的地西安，实际：" + answer);
        assertTrue(answer.contains("编") || answer.contains("没有") || answer.contains("暂无"),
                "应表达「暂无/不编造」的诚实态度，实际：" + answer);
    }

    @Test
    void switchingDestinationResetsDateAndBudget() {
        // 换目的地后，旧的日期/预算应作废，不能沿用莫干山的日期预算去搜新目的地
        // T1：莫干山 + 完整信息 → 完成推荐
        String t1 = agentService.run("cd2", "莫干山，11月1日到3日，预算每晚800，情侣", sink());
        assertTrue(t1.contains("主推") || t1.contains("候选"), "莫干山应正常推荐");

        steps.clear();
        texts.clear();

        // T2：换到西安（数据层无西安），仅带关系 → 应作废旧日期预算，诚实告知西安无数据
        String t2 = agentService.run("cd2", "我改主意了，想去西安，和朋友俩人", sink());
        assertFalse(t2.contains("2027-11-01") && t2.contains("800"),
                "换目的地后不应沿用旧日期预算，实际：" + t2);
        assertTrue(t2.contains("西安"), "应识别新目的地西安");
    }

    @Test
    void switchingToSupportedCityRecommendsItsRooms() {
        // 换到有数据的新城市（成都），应正常推荐成都房源，而非提示「暂无」
        // T1：莫干山 + 完整信息 → 完成推荐
        String t1 = agentService.run("cd4", "莫干山，11月1日到3日，预算每晚800，情侣", sink());
        assertTrue(t1.contains("主推") || t1.contains("候选"), "莫干山应正常推荐");

        steps.clear();
        texts.clear();

        // T2：换到成都 + 完整信息 → 应推荐成都房源（数据层已补成都数据）
        String t2 = agentService.run("cd4", "改去成都，11月1日到3日，预算每晚800，情侣", sink());
        assertTrue(t2.contains("主推") || t2.contains("候选"),
                "成都有数据，应正常推荐而非提示暂无，实际：" + t2);
        // 推荐内容应是成都房源（春熙路/太古里/宽窄巷子等成都片区特征词）
        assertTrue(t2.contains("春熙路") || t2.contains("太古里") || t2.contains("宽窄巷子") || t2.contains("锦里"),
                "应推荐成都片区房源，实际：" + t2);
    }

    @Test
    void switchingToNonWhitelistCityDoesNotResidueOldDestination() {
        // 回归背景（2026-11-17）：目的地识别曾写死白名单，换到白名单外城市会残留旧目的地
        // 现重构为数据层驱动 + 通用正则兜底，任意城市切换都不应残留旧值
        String t1 = agentService.run("cd3", "我想去成都玩", sink());
        assertTrue(t1.contains("成都"), "成都应被识别");

        steps.clear();
        texts.clear();

        // 换「重庆」（曾因白名单识别不到 → destination 残留成都）
        String t2 = agentService.run("cd3", "改去重庆吧", sink());
        assertTrue(t2.contains("重庆"), "应识别新目的地重庆，而非残留成都，实际：" + t2);
        assertFalse(t2.contains("成都"), "不应再提成都，实际：" + t2);
    }
}
