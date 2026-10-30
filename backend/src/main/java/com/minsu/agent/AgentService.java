package com.minsu.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

import com.minsu.agent.llm.LlmClient;
import com.minsu.agent.llm.LlmMessage;
import com.minsu.agent.step.StepEvent;
import com.minsu.agent.step.StepEventType;
import com.minsu.tool.ToolDispatcher;
import com.minsu.tool.ToolResult;

/**
 * Agent 层核心 —— 七步收敛循环 + 思考链事件。
 *
 * <p>对齐《框架设计蓝图》第 5 章。设计要点：
 * <ul>
 *   <li>LLM 驱动 function-calling 循环，但七步收敛的骨架是确定性的；</li>
 *   <li>每走一步向前端推 {@link StepEvent}（思考链可视化数据源）；</li>
 *   <li>缺口识别（第 2 步）与逼决策（第 7 步）由代码保证硬约束，不依赖 LLM 自由发挥。</li>
 * </ul>
 *
 * <p>消费方式：调用 {@link #run(String, String, EventSink)}，事件通过 {@link EventSink} 回调
 * 逐步吐出（SSE 层据此流式推给前端）。
 */
@Service
public class AgentService {

    private final ToolDispatcher toolDispatcher;
    private final LlmClient llmClient;
    private final ConversationMemory conversationMemory;

    /** 会话级状态缓存（sessionId -> AgentState），支撑多轮收敛。 */
    private final ConcurrentMap<String, AgentState> sessions = new ConcurrentHashMap<>();

    public AgentService(ToolDispatcher toolDispatcher, LlmClient llmClient, ConversationMemory conversationMemory) {
        this.toolDispatcher = toolDispatcher;
        this.llmClient = llmClient;
        this.conversationMemory = conversationMemory;
    }

    /** 思考链/文本事件回调。 */
    public interface EventSink {
        /** 推一个思考链步骤事件。 */
        void step(StepEvent event);

        /** 推一段文本（可多次，逐段渲染打字机效果）。 */
        void text(String text);
    }

    /**
     * 执行一次 Agent 收敛（按 sessionId 缓存状态，支持多轮）。
     *
     * @param sessionId 会话 ID（同一会话多轮共享 AgentState，实现「分轮收敛」）
     * @param userInput 用户当前这句话
     * @param sink      事件回调
     * @return 最终文本回复
     */
    public String run(String sessionId, String userInput, EventSink sink) {
        // 取旧会话状态（多轮累积），无则新建
        AgentState state = sessions.computeIfAbsent(sessionId, k -> new AgentState());
        // 对话记忆：本会话之前的问答历史，供最终话术生成时带上上下文
        List<LlmMessage> history = conversationMemory.recent(sessionId);

        // ---- 第 1 步：实体抽取（合并到旧状态，非空才覆盖，已填的保留）----
        sink.step(StepEvent.of(StepEventType.EXTRACTING, "正在理解你的需求…"));
        extractEntities(userInput, state);

        // ---- 第 2 步：缺口识别 ----
        sink.step(StepEvent.of(StepEventType.GAP_FOUND, "检查还缺哪些关键信息…"));
        detectGaps(state);

        // 抽取结果可视化：把已识别的实体摘要推给前端（增强可解释性）
        sink.step(StepEvent.of(StepEventType.EXTRACTING, summarizeExtracted(state)));

        // 若缺必问三要素 → 第 3 步带着方案问，本轮终止（不筛选）
        if (!state.getGaps().isEmpty()) {
            sink.step(StepEvent.of(StepEventType.GAP_FOUND, summarizeGaps(state)));
            String askAnswer = askWithOptions(sink, state);
            // 追问也是一轮对话，记入记忆
            conversationMemory.appendTurn(sessionId, userInput, askAnswer);
            return askAnswer;
        }

        // ---- 第 4 步：调用地域知识 ----
        sink.step(StepEvent.of(StepEventType.LOADING_KNOWLEDGE, "加载" + state.getDestination() + "片区知识…"));

        // ---- 第 5 步：四轴筛选 ----
        sink.step(StepEvent.of(StepEventType.FILTERING, "从候选房源中筛选…"));
        List<Map<String, Object>> candidates = filterCandidates(sessionId, state);
        state.setCandidates(candidates);
        sink.step(StepEvent.of(StepEventType.FILTERING,
                "按「目的地=" + state.getDestination() + "、日期=" + state.getCheckIn() + "~" + state.getCheckOut()
                        + "、预算≤" + state.getBudgetPerNight() + "、关系=" + state.getRelationship()
                        + "」筛出 " + candidates.size() + " 套候选"));

        // ---- 第 6 步：包装方案 ----
        sink.step(StepEvent.of(StepEventType.PACKAGING, "组织推荐方案…"));

        // ---- 第 7 步：逼出决策（LLM 生成最终话术，硬约束由代码保证）----
        sink.step(StepEvent.of(StepEventType.DECIDING, "给出最终建议…"));
        String finalAnswer = generateFinalAnswer(state, history);

        sink.text(finalAnswer);
        // 本轮问答写入对话记忆，供下一轮延续上下文
        conversationMemory.appendTurn(sessionId, userInput, finalAnswer);
        return finalAnswer;
    }

    // ---------------------------------------------------------------- 各步实现

    /** 第 1 步：实体抽取。用规则 + 轻量启发式（不依赖 LLM，保证确定性）。 */
    private void extractEntities(String input, AgentState state) {
        // 目的地：莫干山为当前唯一样本
        if (input.contains("莫干山")) {
            state.setDestination("莫干山");
        }
        // 日期：匹配 YYYY-MM-DD / YYYY/MM/DD / M月D日（无年份，默认今年）
        // 支持三种表达：
        //   1) 范围「11月1日到3日」「11月28日到12月2日」
        //   2) 单日期+时长「11月1日，就一天」「11月1日住两晚」「11月1日住3天」
        //   3) 纯数字「11月1日」（单日默认住 1 晚，checkOut = checkIn + 1 天）
        // 第二段月份用捕获组：存在则用第二段月份，否则沿用第一段月份
        java.time.LocalDate checkInDate = null;
        java.time.LocalDate checkOutDate = null;

        java.util.regex.Pattern rangePat = java.util.regex.Pattern
                .compile("(20\\d{2})?\\s*年?\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?\\s*(?:到|至|~|-)\\s*(?:(\\d{1,2})\\s*月)?\\s*(\\d{1,2})\\s*[日号]?");
        java.util.regex.Matcher rm = rangePat.matcher(input);
        if (rm.find()) {
            String year = rm.group(1) == null ? "2026" : rm.group(1);
            String checkInMonth = rm.group(2);
            String checkInDay = rm.group(3);
            // 第二段月份（group 4）可能为 null（同月），null 时沿用第一段月份
            String checkOutMonth = rm.group(4) == null ? checkInMonth : rm.group(4);
            String checkOutDay = rm.group(5);
            checkInDate = java.time.LocalDate.of(
                    Integer.parseInt(year), Integer.parseInt(checkInMonth), Integer.parseInt(checkInDay));
            checkOutDate = java.time.LocalDate.of(
                    Integer.parseInt(year), Integer.parseInt(checkOutMonth), Integer.parseInt(checkOutDay));
        } else {
            // 无「到/至」范围 → 找单个「M月D日」
            java.util.regex.Matcher single = java.util.regex.Pattern
                    .compile("(20\\d{2})?\\s*年?\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?")
                    .matcher(input);
            if (single.find()) {
                String year = single.group(1) == null ? "2026" : single.group(1);
                checkInDate = java.time.LocalDate.of(
                        Integer.parseInt(year),
                        Integer.parseInt(single.group(2)),
                        Integer.parseInt(single.group(3)));
                // 由「住N晚/住N天/就一天」推导 checkOut；否则默认住 1 晚
                int nights = 1;
                java.util.regex.Matcher nm = java.util.regex.Pattern
                        .compile("(?:住|待|停留|就|玩|一共)\\s*(\\d{1,2})\\s*(?:晚|天|夜)")
                        .matcher(input);
                if (nm.find()) {
                    nights = Integer.parseInt(nm.group(1));
                } else if (input.contains("就一天") || input.contains("只住一天") || input.contains("住一晚") || input.contains("一天")) {
                    nights = 1;
                }
                checkOutDate = checkInDate.plusDays(nights);
            }
        }
        if (checkInDate != null && checkOutDate != null) {
            state.setCheckIn(checkInDate.toString());
            state.setCheckOut(checkOutDate.toString());
        }
        // 预算：优先匹配「预算/人均/每晚/价格」关键字后的数字；
        // 若本轮没有关键字，但出现独立裸数字（如「800」），也识别为预算（多轮补充场景）。
        java.util.regex.Matcher bm = java.util.regex.Pattern
                .compile("(?:预算|人均|每晚|价格|价位|一天|一晚|左右|大概)\\D{0,4}(\\d{2,5})\\s*(?:元|块)?")
                .matcher(input);
        boolean budgetMatched = false;
        if (bm.find()) {
            state.setBudgetPerNight(Integer.parseInt(bm.group(1)));
            budgetMatched = true;
        }
        if (!budgetMatched && state.getBudgetPerNight() == null) {
            // 裸数字兜底：一句话里只有一个 3 位以上数字且无日期上下文，视为预算
            java.util.regex.Matcher bare = java.util.regex.Pattern
                    .compile("(?<![\\d月])(\\d{3,4})(?![\\d月])")
                    .matcher(input);
            if (bare.find()) {
                int v = Integer.parseInt(bare.group(1));
                if (v >= 200 && v <= 5000) {
                    state.setBudgetPerNight(v);
                }
            }
        }
        // 出行关系
        if (input.contains("情侣") || input.contains("两个人") || input.contains("2人") || input.contains("二人")
                || input.contains("两人")) {
            state.setRelationship("情侣");
        } else if (input.contains("朋友") || input.contains("闺蜜") || input.contains("兄弟")) {
            state.setRelationship("朋友");
        } else if (input.contains("亲子") || input.contains("孩子") || input.contains("带娃") || input.contains("小孩")) {
            state.setRelationship("亲子");
        } else if (input.contains("家庭") || input.contains("爸妈") || input.contains("父母") || input.contains("全家")) {
            state.setRelationship("家庭");
        }
        // 人数：显式「N人/个大人/N个小孩」优先，否则按关系推断（已有值则不覆盖）
        java.util.regex.Matcher gm = java.util.regex.Pattern
                .compile("(\\d{1,2})\\s*(?:个|位)?\\s*(?:大人|成人|人|个小孩|个孩子|个娃)")
                .matcher(input);
        if (gm.find()) {
            state.setGuests(Integer.parseInt(gm.group(1)));
        } else if (state.getGuests() == null && state.getRelationship() != null) {
            state.setGuests(switch (state.getRelationship()) {
                case "情侣" -> 2;
                case "朋友" -> 2;
                case "亲子" -> 3;
                case "家庭" -> 4;
                default -> 2;
            });
        }
        // 风格
        for (String s : List.of("设计", "原木", "网红", "竹海", "溪流")) {
            if (input.contains(s)) {
                state.setStyle(s);
                break;
            }
        }
    }

    /** 汇总已抽取的实体，输出一段可读摘要（思考链可视化用）。 */
    private String summarizeExtracted(AgentState state) {
        StringBuilder sb = new StringBuilder("已识别：");
        List<String> parts = new ArrayList<>();
        if (state.getDestination() != null) parts.add("目的地=" + state.getDestination());
        if (state.getCheckIn() != null) parts.add("入住=" + state.getCheckIn());
        if (state.getCheckOut() != null) parts.add("离店=" + state.getCheckOut());
        if (state.getBudgetPerNight() != null) parts.add("预算≤" + state.getBudgetPerNight() + "元/晚");
        if (state.getRelationship() != null) parts.add("关系=" + state.getRelationship());
        if (state.getGuests() != null) parts.add("人数=" + state.getGuests());
        if (state.getStyle() != null) parts.add("风格=" + state.getStyle());
        return parts.isEmpty() ? "暂未识别到明确需求，等待补充" : sb.append(String.join("、", parts)).toString();
    }

    /** 汇总缺口，输出可读说明（思考链可视化用）。 */
    private String summarizeGaps(AgentState state) {
        if (state.getGaps().isEmpty()) return "关键信息已齐全，进入筛选";
        return "还缺 " + state.getGaps().size() + " 项：" + String.join("、", state.getGaps());
    }

    /** 第 2 步：缺口识别。必问三要素：日期/预算/关系。每轮重算，避免累积。 */
    private void detectGaps(AgentState state) {
        state.getGaps().clear();
        if (state.getCheckIn() == null || state.getCheckOut() == null) {
            state.getGaps().add("具体日期");
        }
        if (state.getBudgetPerNight() == null) {
            state.getGaps().add("每晚预算");
        }
        if (state.getRelationship() == null) {
            state.getGaps().add("出行关系");
        }
    }

    /** 第 3 步：带着方案问（≤3 问 + 兜底方向）。 */
    private String askWithOptions(EventSink sink, AgentState state) {
        sink.step(StepEvent.of(StepEventType.ASKING, "带着方案向你确认…"));
        StringBuilder sb = new StringBuilder();
        sb.append("为了给你最准的推荐，还差 ").append(state.getGaps().size()).append(" 个信息：\n");
        for (String g : state.getGaps()) {
            sb.append("- ").append(g);
            if ("具体日期".equals(g)) {
                sb.append("（周末平日价差 30%-60%，影响很大）");
            } else if ("每晚预算".equals(g)) {
                sb.append("（400 农家乐 vs 1500 设计师是两个方向）");
            } else if ("出行关系".equals(g)) {
                sb.append("（情侣/朋友/亲子的房型逻辑不同）");
            }
            sb.append("\n");
        }
        sb.append("\n如果你没想好，我可以按「").append(state.getDestination() == null ? "莫干山" : state.getDestination())
          .append(" · 平日 · 人均 800 左右」先给你一版参考，你再调整。");
        String answer = sb.toString();
        sink.text(answer);
        return answer;
    }

    /** 第 5 步：四轴筛选（调工具层 search_rooms）。 */
    private List<Map<String, Object>> filterCandidates(String userId, AgentState state) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("destination", state.getDestination() == null ? "莫干山" : state.getDestination());
        if (state.getCheckIn() != null) args.put("checkIn", state.getCheckIn());
        if (state.getCheckOut() != null) args.put("checkOut", state.getCheckOut());
        if (state.getBudgetPerNight() != null) args.put("budgetPerNight", state.getBudgetPerNight());
        if (state.getRelationship() != null) args.put("relationship", state.getRelationship());
        if (state.getStyle() != null) args.put("style", state.getStyle());
        if (state.getGuests() != null) args.put("guests", state.getGuests());

        ToolResult result = toolDispatcher.dispatch(userId, "search_rooms", args);
        if (!result.success()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rooms = (List<Map<String, Object>>) result.data().get("rooms");
        return rooms == null ? List.of() : rooms;
    }

    /** 第 7 步：逼出决策（硬约束由代码组装，LLM 润色话术）。 */
    private String generateFinalAnswer(AgentState state, List<LlmMessage> history) {
        List<Map<String, Object>> candidates = state.getCandidates();
        if (candidates.isEmpty()) {
            return "当前条件下没有找到匹配房源，建议放宽预算或日期后我再帮你筛。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("我筛出了 ").append(candidates.size()).append(" 套，地段/价位/风格都有差异：\n\n");

        int show = Math.min(3, candidates.size());
        for (int i = 0; i < show; i++) {
            Map<String, Object> c = candidates.get(i);
            sb.append("【候选 ").append(i + 1).append("】").append(c.get("title"))
              .append("（").append(c.get("area")).append(" · ").append(c.get("style")).append("）\n");
            sb.append("定位：").append(c.get("positioning")).append("\n");
            sb.append("价格：").append(c.get("basePrice")).append(" 元/晚起\n");
            sb.append("适配：").append(c.get("fitReason")).append("\n");
            sb.append("缺点：").append(c.get("drawback")).append("\n\n");
        }

        // 主推 = 第一个候选（由筛选顺序保证）
        Map<String, Object> main = candidates.get(0);
        sb.append("✅ 主推：").append(main.get("title")).append("\n");
        sb.append("理由：").append(main.get("fitReason")).append("\n");
        sb.append("如果").append(main.get("drawback")).append("，改推【候选 2】。\n\n");
        sb.append("下一步：确认的话，我对这套报价，你看含全部费用的总价后决定要不要订。");

        String template = sb.toString();
        // 已配置 LLM 时润色话术（不改变候选与硬约束信息），失败则回退模板
        return polishWithLlm(state, template, history);
    }

    /** 用 LLM 润色最终话术（未配置或异常时回退模板，保证硬约束不丢）。 */
    private String polishWithLlm(AgentState state, String template, List<LlmMessage> history) {
        if (!llmClient.isConfigured()) {
            return template;
        }
        try {
            List<LlmMessage> messages = new ArrayList<>();
            messages.add(LlmMessage.system("你是民宿预约顾问。请把下面的推荐润色得自然、有温度，"
                    + "但必须保留所有候选、主推、缺点、下一步动作等信息，不要增删房源事实。"
                    + "你与用户正在进行连续对话，对话历史附在下方：润色时带上上下文，"
                    + "用户之前的提问与你之前的回答都是背景，语气上体现「我记得我们刚才聊的内容」。"));
            messages.addAll(history);
            messages.add(LlmMessage.user(template));
            LlmClient.LlmResponse resp = llmClient.chat(messages, List.of());
            if (resp != null && resp.content() != null && !resp.content().isBlank()) {
                return resp.content();
            }
        } catch (Exception e) {
            // 润色失败不影响主流程，回退模板
        }
        return template;
    }

    private static String pad(String s) {
        return s.length() == 1 ? "0" + s : s;
    }
}
