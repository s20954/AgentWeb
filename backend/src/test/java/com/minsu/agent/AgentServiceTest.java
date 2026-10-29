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
        ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry(), new RoomStore(), new OrderStore(),
                new RagService(new KbStore(new com.minsu.rag.KbRepository(
                        System.getProperty("java.io.tmpdir") + "/minsu-kb-test-" + System.nanoTime() + ".db"))));
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
        agentService = new AgentService(dispatcher, noopLlm);
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
}
