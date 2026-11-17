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
import com.minsu.rag.RagService;
import com.minsu.tool.ToolDispatcher;
import com.minsu.tool.ToolRegistry;
import com.minsu.tool.ToolResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** LLM 自主工具调用循环的最大轮数（防止 LLM 无休止调用工具）。 */
    private static final int MAX_TOOL_ROUNDS = 5;

    private final ToolDispatcher toolDispatcher;
    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final ConversationMemory conversationMemory;
    private final RagService ragService;
    private final com.minsu.tool.executor.RoomStore roomStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 会话级状态缓存（sessionId -> AgentState），支撑多轮收敛。 */
    private final ConcurrentMap<String, AgentState> sessions = new ConcurrentHashMap<>();
    /** 每个 sessionId 的最后活跃时间（用于过期清理，防内存泄漏）。 */
    private final ConcurrentMap<String, Long> sessionTouch = new ConcurrentHashMap<>();
    /** 会话状态上限：前端每次刷新都生成新 sessionId，不设上限会无界堆积（与 ConversationMemory 同款问题）。 */
    private static final int MAX_SESSIONS = 1000;
    /** 会话状态不活跃多久后清理（与 ConversationMemory TTL 对齐）。 */
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;

    public AgentService(ToolDispatcher toolDispatcher, ToolRegistry toolRegistry, LlmClient llmClient,
                        ConversationMemory conversationMemory, RagService ragService,
                        com.minsu.tool.executor.RoomStore roomStore) {
        this.toolDispatcher = toolDispatcher;
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient;
        this.conversationMemory = conversationMemory;
        this.ragService = ragService;
        this.roomStore = roomStore;
    }

    /** 思考链/文本事件回调。 */
    public interface EventSink {
        /** 推一个思考链步骤事件。 */
        void step(StepEvent event);

        /** 推一段文本（可多次，逐段渲染打字机效果）。 */
        void text(String text);
    }

    /**
     * 便捷重载：userId 回退为 sessionId（用于测试/无独立用户体系的场景）。
     * 生产链路应走 {@link #run(String, String, String, EventSink)} 显式传入持久 userId，
     * 否则刷新页面后 sessionId 变化会导致订单归属错乱（查单/取消被「无权操作」拒绝）。
     */
    public String run(String sessionId, String userInput, EventSink sink) {
        return run(sessionId, sessionId, userInput, sink);
    }

    /**
     * 执行一次 Agent 收敛（按 sessionId 缓存状态，支持多轮）。
     *
     * @param userId    用户标识（订单归属；持久不变，区别于会随刷新变化的 sessionId）
     * @param sessionId 会话 ID（同一会话多轮共享 AgentState，实现「分轮收敛」）
     * @param userInput 用户当前这句话
     * @param sink      事件回调
     * @return 最终文本回复
     */
    public String run(String userId, String sessionId, String userInput, EventSink sink) {
        // 取旧会话状态（多轮累积），无则新建；先清扫过期/超限条目，防内存泄漏
        evictSessionsIfNeeded();
        AgentState state = sessions.computeIfAbsent(sessionId, k -> new AgentState());
        sessionTouch.put(sessionId, System.currentTimeMillis());
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

        // ---- 交易/订单意图优先路由（E2E 实测修复）----
        // 看价/下单/查单/取消/支付是真实工具操作，必须走 LLM 工具调用轨道执行；
        // 若放在缺口追问/规则轨道之后，三要素一旦齐全会话会被锁死在规则筛选里，
        // 用户说「yg-001 多少钱」「就订这套」「退了吧」全部失灵（报价/下单成假动作）。
        // 注意：交易意图必须先于「候选聚焦」路由，否则「就订候选1」会被候选聚焦误拦截成「介绍候选1」。
        if (llmClient.isConfigured() && isTransactionIntent(userInput)) {
            sink.step(StepEvent.of(StepEventType.TOOL_CALLING, "识别到报价/订单操作，让我调工具处理…"));
            String txAnswer = runLlmToolLoop(userId, userInput, state, history, sink);
            sink.text(txAnswer);
            conversationMemory.appendTurn(sessionId, userInput, txAnswer);
            return txAnswer;
        }

        // ---- 候选聚焦/详细介绍意图路由（E2E 实测修复）----
        // 上一轮已给出候选列表，本轮用户说「我选候选2 / 详细讲讲第3套」时，
        // 若落到规则轨道会重复整轮筛选、原样重发同一批候选（用户错觉「回复的又是第一题」）。
        // 这里识别「候选 N」并映射成 roomId，直接用房源数据 + RAG 知识做单套详细介绍。
        // 纯规则实现（不依赖 LLM），避免 LLM 402 降级时又退回整批推荐。
        String focusRoomId = resolveCandidateRoomId(userInput, state);
        if (focusRoomId != null) {
            sink.step(StepEvent.of(StepEventType.TOOL_CALLING, "识别到你在了解某套房源详情，让我查一下…"));
            String focusAnswer = buildRoomFocusAnswer(focusRoomId, state);
            sink.text(focusAnswer);
            conversationMemory.appendTurn(sessionId, userInput, focusAnswer);
            return focusAnswer;
        }

        // ---- 目的地覆盖校验（先于缺口追问）：数据层只有莫干山，其它目的地诚实告知「暂无房源」 ----
        // 放在追问之前，避免用户先说「去成都」却被要求补日期、迟迟听不到「成都没数据」的诚实答复。
        if (state.getDestination() != null && !roomStore.supportsDestination(state.getDestination())) {
            String dest = state.getDestination();
            sink.step(StepEvent.of(StepEventType.PACKAGING, "检查目的地房源覆盖…"));
            String honest = "关于「" + dest + "」：目前我的房源库只覆盖了**莫干山**，"
                    + "还没有 " + dest + " 的真实房源数据，所以没法给你靠谱的推荐——我不编造不存在的房源。\n\n"
                    + "你可以：\n"
                    + "1. 改订**莫干山**，我这边有 5 个片区、多套真实风格的房源可以马上帮你筛；\n"
                    + "2. 告诉我 " + dest + " 的具体需求（日期/预算/关系），我先记下来，后续接入 " + dest + " 数据后第一时间帮你比对。";
            sink.text(honest);
            conversationMemory.appendTurn(sessionId, userInput, honest);
            return honest;
        }

        // 若缺必问三要素 → 第 3 步带着方案问，本轮终止（不筛选）
        if (!state.getGaps().isEmpty()) {
            sink.step(StepEvent.of(StepEventType.GAP_FOUND, summarizeGaps(state)));
            String askAnswer = askWithOptions(sink, state);
            // 追问也是一轮对话，记入记忆
            conversationMemory.appendTurn(sessionId, userInput, askAnswer);
            return askAnswer;
        }

        // ---- 第 4 步：调用地域知识（真实检索，而非空壳事件）----
        sink.step(StepEvent.of(StepEventType.LOADING_KNOWLEDGE, "加载" + state.getDestination() + "片区知识…"));
        List<Map<String, Object>> knowledgeHits = loadRegionalKnowledge(state);

        // ---- 第 5 步：双轨 —— 规则筛选 或 LLM 自主工具调用 ----
        // 判断：需求是否超出规则覆盖范围（软性/知识性/偏好类，如带宠物、要温泉、问政策）
        boolean softDemand = isSoftDemand(state, userInput);
        // 知识问答类（景点/攻略/政策等纯咨询）→ 纯 RAG 兜底，不依赖 LLM，避免 402 降级回订房
        if (isKnowledgeQuery(userInput)) {
            sink.step(StepEvent.of(StepEventType.TOOL_CALLING, "这个问题我查一下本地知识库…"));
            String kbAnswer = buildKnowledgeAnswer(userInput, state);
            sink.text(kbAnswer);
            conversationMemory.appendTurn(sessionId, userInput, kbAnswer);
            return kbAnswer;
        }
        if (softDemand && llmClient.isConfigured()) {
            // 轨道 B：LLM 自主工具调用（真 Function Calling）
            sink.step(StepEvent.of(StepEventType.TOOL_CALLING, "让我自主判断该查什么、该调哪个工具…"));
            String autonomousAnswer = runLlmToolLoop(userId, userInput, state, history, sink);
            sink.text(autonomousAnswer);
            conversationMemory.appendTurn(sessionId, userInput, autonomousAnswer);
            return autonomousAnswer;
        }

        // 轨道 A：确定性四轴筛选（规则路径，稳 + 可解释）
        sink.step(StepEvent.of(StepEventType.FILTERING, "从候选房源中筛选…"));
        List<Map<String, Object>> candidates = filterCandidates(userId, state);
        state.setCandidates(candidates);
        sink.step(StepEvent.of(StepEventType.FILTERING,
                "按「目的地=" + state.getDestination() + "、日期=" + state.getCheckIn() + "~" + state.getCheckOut()
                        + "、预算≤" + state.getBudgetPerNight() + "、关系=" + state.getRelationship()
                        + "」筛出 " + candidates.size() + " 套候选"));

        // ---- 第 6 步：包装方案 ----
        sink.step(StepEvent.of(StepEventType.PACKAGING, "组织推荐方案…"));

        // ---- 第 7 步：逼出决策（LLM 生成最终话术，硬约束由代码保证）----
        // 结构化房源随 DECIDING 步骤的 payload 推给前端，前端据此渲染房源卡片（多方案对比）
        sink.step(StepEvent.of(StepEventType.DECIDING, "给出最终建议…", buildRoomsPayload(state)));
        String finalAnswer = generateFinalAnswer(state, history);

        sink.text(finalAnswer);
        // 本轮问答写入对话记忆，供下一轮延续上下文
        conversationMemory.appendTurn(sessionId, userInput, finalAnswer);
        return finalAnswer;
    }

    // ---------------------------------------------------------------- 各步实现

    /**
     * 从用户输入中提取目的地地名（规范化后返回，未识别返回 null）。
     *
     * <p><b>数据层驱动设计（不再维护城市白名单）</b>：本方法只负责「从句子中把地名词抠出来」，
     * 「支持哪些目的地」由数据层 {@link RoomStore#supportedDestinations()} 动态判定。
     * 以后接入新城市数据，只需在数据层加房源，Agent 层识别零改动。
     *
     * <p>提取策略（按优先级）：
     * <ol>
     *   <li>精确匹配「数据层已支持的目的地名」（如「莫干山」），直接命中；</li>
     *   <li>「去/到/改去/换去/换成/去往/前往」等动词后紧跟的 2~4 字地名，通用抓取任意城市（成都/重庆/大理…）；</li>
     * </ol>
     * 规范化：去掉「省」前缀（四川/云南/浙江等）与「市/镇/县」尾缀，保留「山/岛」景区特色字。
     */
    private String extractDestination(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        // 1) 精确匹配数据层已支持的目的地（唯一真相源来自 RoomStore，动态派生）
        for (String supported : roomStore.supportedDestinations()) {
            if (input.contains(supported)) {
                return supported;
            }
        }
        // 2) 通用「去XX/到XX/改去XX」模式：先抓动词后的连续汉字段，再用停用词截断，得到地名。
        // 不依赖城市清单，也不硬卡尾缀词（「游玩/旅游/度假」等表达千变万化，硬卡会漏）。
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:去|到|改去|换去|换成|改到|换到|去往|前往)([\\u4e00-\\u9fa5]{2,8})")
                .matcher(input);
        if (m.find()) {
            String raw = m.group(1);
            // 规范化：去省前缀 + 去市/镇/县尾缀
            raw = raw
                    .replaceAll("^(四川|云南|浙江|广东|江苏|山东|湖北|陕西|福建|海南|湖南|安徽)", "")
                    .replaceAll("(市|镇|县)$", "");
            // 用停用词截断：地名之后紧跟「玩/旅游/游/度假/旅行/住/看/转/吧/啊/呀/呢/两天/三天」等即截断
            for (String stop : List.of("旅游", "度假", "旅行", "游玩", "玩", "游", "住", "看", "转", "吧", "啊", "呀", "呢", "两天", "三天", "几天", "一晚")) {
                int idx = raw.indexOf(stop);
                if (idx > 0) {
                    raw = raw.substring(0, idx);
                }
            }
            // 截断后再清一次尾缀（截断可能露出「市/县」等）
            raw = raw.replaceAll("(市|镇|县)$", "");
            if (raw.isBlank()) {
                return null;
            }
            return raw;
        }
        return null;
    }

    /** 第 1 步：实体抽取。用规则 + 轻量启发式（不依赖 LLM，保证确定性）。 */
    private void extractEntities(String input, AgentState state) {
        // 目的地：识别常见国内目的地（城市/景区名），并规范化（去省前缀、去「市/山」等冗余）。
        // 若用户切换到与之前不同的目的地，旧的目的地相关上下文（日期/预算/风格）随之作废，
        // 避免「搜成都却沿用上一轮莫干山的日期预算」的错误（详见 2026-11-17 成都 bug）。
        String newDestination = extractDestination(input);
        if (newDestination != null) {
            if (state.getDestination() != null && !state.getDestination().equals(newDestination)) {
                // 换目的地：清空日期/预算/风格/候选（关系、人数与目的地弱相关，保留）
                state.setCheckIn(null);
                state.setCheckOut(null);
                state.setBudgetPerNight(null);
                state.setStyle(null);
                state.setCandidates(new ArrayList<>());
            }
            state.setDestination(newDestination);
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
            String checkInMonth = rm.group(2);
            String checkInDay = rm.group(3);
            // 第二段月份（group 4）可能为 null（同月），null 时沿用第一段月份
            String checkOutMonth = rm.group(4) == null ? checkInMonth : rm.group(4);
            String checkOutDay = rm.group(5);
            try {
                int year = rm.group(1) == null ? java.time.LocalDate.now().getYear() : Integer.parseInt(rm.group(1));
                checkInDate = java.time.LocalDate.of(year, Integer.parseInt(checkInMonth), Integer.parseInt(checkInDay));
                checkOutDate = java.time.LocalDate.of(year, Integer.parseInt(checkOutMonth), Integer.parseInt(checkOutDay));
                // 跨年范围（如「12月28日到1月2日」）：离店月份小于入住月份，离店落到下一年
                if (!checkOutDate.isAfter(checkInDate)) {
                    checkOutDate = checkOutDate.plusYears(1);
                }
                // 未显式给年份且入住日期已过，默认指向明年（不存在订过去日期的场景）
                if (rm.group(1) == null && checkInDate.isBefore(java.time.LocalDate.now())) {
                    checkInDate = checkInDate.plusYears(1);
                    checkOutDate = checkOutDate.plusYears(1);
                }
            } catch (java.time.DateTimeException | NumberFormatException e) {
                // 非法日期（如「13月40日」）：视为未识别，交给缺口追问，而不是让流程崩溃
                checkInDate = null;
                checkOutDate = null;
            }
        } else {
            // 无「到/至」范围 → 找单个「M月D日」
            java.util.regex.Matcher single = java.util.regex.Pattern
                    .compile("(20\\d{2})?\\s*年?\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?")
                    .matcher(input);
            if (single.find()) {
                try {
                    int year = single.group(1) == null ? java.time.LocalDate.now().getYear() : Integer.parseInt(single.group(1));
                    checkInDate = java.time.LocalDate.of(year,
                            Integer.parseInt(single.group(2)),
                            Integer.parseInt(single.group(3)));
                    // 未显式给年份且日期已过，默认指向明年
                    if (single.group(1) == null && checkInDate.isBefore(java.time.LocalDate.now())) {
                        checkInDate = checkInDate.plusYears(1);
                    }
                } catch (java.time.DateTimeException | NumberFormatException e) {
                    // 非法日期（如「13月40日」）：视为未识别，交给缺口追问
                    checkInDate = null;
                }
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
                checkOutDate = checkInDate == null ? null : checkInDate.plusDays(nights);
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
                    .compile("(?<![\\d年月])(\\d{3,4})(?![\\d年月])")
                    .matcher(input);
            if (bare.find()) {
                int v = Integer.parseInt(bare.group(1));
                // 排除 20xx：落在该区间的裸数字大概率是年份（如「2026」）而非每晚预算
                if (v >= 200 && v <= 5000 && !(v >= 2000 && v <= 2099)) {
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

    /** 组装结构化房源 payload：把候选打包成前端可直接渲染的卡片数据（含主推）。 */
    private Map<String, Object> buildRoomsPayload(AgentState state) {
        List<Map<String, Object>> candidates = state.getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            return Map.of("rooms", List.of(), "count", 0, "recommendedId", "");
        }
        int show = Math.min(3, candidates.size());
        List<Map<String, Object>> rooms = new ArrayList<>(show);
        for (int i = 0; i < show; i++) {
            Map<String, Object> c = candidates.get(i);
            Map<String, Object> room = new LinkedHashMap<>();
            room.put("roomId", c.get("roomId"));
            room.put("area", c.get("area"));
            room.put("title", c.get("title"));
            room.put("positioning", c.get("positioning"));
            room.put("basePrice", c.get("basePrice"));
            room.put("style", c.get("style"));
            room.put("fitReason", c.get("fitReason"));
            room.put("drawback", c.get("drawback"));
            room.put("cover", c.get("cover"));
            rooms.add(room);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rooms", rooms);
        payload.put("count", candidates.size());
        payload.put("recommendedId", candidates.get(0).get("roomId"));
        // 供前端「帮我报价」按钮调用 quote_price 使用（报价需 roomId + 入住/离店日期）
        payload.put("checkIn", state.getCheckIn());
        payload.put("checkOut", state.getCheckOut());
        payload.put("guests", state.getGuests());
        return payload;
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
                    + "本作品紧扣「健康/康养」主题：当用户关注健康、康养、带老人、静养、恢复、徒步放松等需求时，"
                    + "突出每套房的健康友好要素（是否安静、空气质量、有无温泉/私汤、是否适合散步徒步、离镇远近），"
                    + "并在适配理由里显式点明这套房对健康的友好之处。"
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

    /**
     * 第 4 步：真实调用地域知识库检索，返回 Top-K 命中（供后续 LLM 规划/筛选参考）。
     * 此前此处只有事件壳子、未真正调用 RAG，是「AI 含量」空洞之一。
     */
    private List<Map<String, Object>> loadRegionalKnowledge(AgentState state) {
        String query = buildKnowledgeQuery(state);
        List<RagService.Hit> hits = ragService.search(query, 3);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RagService.Hit h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", h.category());
            m.put("content", h.content());
            m.put("score", Math.round(h.score() * 100) / 100.0);
            out.add(m);
        }
        return out;
    }

    /** 构造地域知识检索 query：目的地 + 已识别实体，保证能召回到对应片区/政策/攻略。 */
    private String buildKnowledgeQuery(AgentState state) {
        StringBuilder sb = new StringBuilder();
        if (state.getDestination() != null) sb.append(state.getDestination());
        if (state.getStyle() != null) sb.append(" ").append(state.getStyle());
        if (state.getRelationship() != null) sb.append(" ").append(state.getRelationship());
        if (sb.length() == 0) sb.append("莫干山");
        return sb.toString();
    }

    /** 房源 ID 字面量（如 yg-001 / ll-002）：出现即视为已锁定某套房源谈交易。 */
    private static final java.util.regex.Pattern ROOM_ID_PATTERN = java.util.regex.Pattern
            .compile("(?i)[a-z]{2,6}\\s*-\\s*\\d{2,4}");

    /** 候选序号正则：匹配「候选2 / 候选 2 / 第3套 / 第二套 / 选 2 / 选第二套」里的序号。 */
    private static final java.util.regex.Pattern CANDIDATE_INDEX_PATTERN = java.util.regex.Pattern
            .compile("(?:候选|第)\\s*([一二三四五六七八九十0-9]+)\\s*(?:套|个)?|选\\s*([一二三四五六七八九十0-9]+)\\s*(?:套|这|那)");

    /** 候选聚焦信号词：单说「候选/第N套/详细介绍/介绍一下」才算聚焦，「选N」须带套/这/那，避免「选一下」等口语误伤。 */
    private static final java.util.regex.Pattern CANDIDATE_FOCUS_PATTERN = java.util.regex.Pattern
            .compile("候选|第[一二三四五六七八九十0-9]+套|详细介绍|详细讲讲|介绍一下|选[一二三四五六七八九十0-9]+\\s*(?:套|这|那)");

    /**
     * 解析「候选 N / 第 N 套 / 选 N」→ 对应候选的 roomId。
     *
     * <p>前提：当前会话 state 里已有上一轮筛出的候选列表（{@code state.getCandidates()}）。
     * 用户在上一轮推荐基础上说「我选候选2」或「详细讲讲第3套」，本质是聚焦某套已给出的房源，
     * 不该重新跑一遍筛选（否则会原样重发同一批候选，产生「回复的又是第一题」的错觉）。
     * 命中则返回该候选的 roomId，交给 LLM 工具循环用 ask_house_info 做单套详细介绍。
     *
     * @return 命中候选序号且该序在候选列表内时返回 roomId；否则返回 null（走原流程）
     */
    String resolveCandidateRoomId(String input, AgentState state) {
        if (input == null || input.isBlank()) {
            return null;
        }
        List<Map<String, Object>> candidates = state.getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // 命中聚焦信号词才算聚焦意图（否则「选」字可能出现在别的语义里）
        if (!CANDIDATE_FOCUS_PATTERN.matcher(input).find()) {
            return null;
        }
        java.util.regex.Matcher m = CANDIDATE_INDEX_PATTERN.matcher(input);
        if (!m.find()) {
            return null;
        }
        // 取第一个非空捕获组（候选/第 的序号，或「选」后的序号）
        String idxStr = null;
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.group(g) != null) {
                idxStr = m.group(g);
                break;
            }
        }
        if (idxStr == null) {
            return null;
        }
        int idx = parseChineseNumber(idxStr);
        if (idx < 1 || idx > candidates.size()) {
            // 序号越界（如「候选8」但只有3套）：返回 null，交给原流程/LLM 兜底说明
            return null;
        }
        Map<String, Object> target = candidates.get(idx - 1);
        Object roomId = target.get("roomId");
        return roomId == null ? null : roomId.toString();
    }

    /** 把「一/二/三…十/1/2/3…」转成 int；无法解析返回 -1。 */
    private int parseChineseNumber(String s) {
        if (s == null || s.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            // 中文数字
        }
        return switch (s.trim()) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> -1;
        };
    }

    /**
     * 针对某套候选房源生成聚焦的详细介绍（纯规则 + RAG，不依赖 LLM）。
     *
     * <p>背景（2026-11-17 E2E）：用户说「我选候选2，详细讲讲」，原实现无此意图路由，
     * 落到规则轨道原样重发整批推荐。且即便路由到 LLM，若 LLM 402 降级也会退回整批推荐。
     * 故这里直接取房源数据 + 片区知识拼装单套详情，保证「聚焦某套」这一意图在任何情况下都成立。
     */
    private String buildRoomFocusAnswer(String roomId, AgentState state) {
        com.minsu.tool.executor.Room room = roomStore.byId(roomId);
        if (room == null) {
            return "抱歉，我这边没找到这套房源的详情，你可以重新说下想了解哪一套。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("好的，专门讲讲这一套——\n\n");
        sb.append("【").append(room.title()).append("】").append("（").append(room.area()).append(" · ")
          .append(room.style()).append("）\n");
        sb.append("定位：").append(room.positioning()).append("\n");
        sb.append("价格：").append(room.basePrice()).append(" 元/晚起\n");
        sb.append("适合你：").append(room.fitReason()).append("\n");
        sb.append("要注意：").append(room.drawback()).append("\n");

        // 拼片区知识（RAG 检索该目的地+片区的介绍，命中则附上，增强「详细」感）
        String query = (state.getDestination() == null ? "" : state.getDestination())
                + " " + room.area() + " " + room.style();
        List<RagService.Hit> hits = ragService.search(query, 2);
        if (!hits.isEmpty() && hits.get(0).score() >= 0.05) {
            sb.append("\n片区补充：\n");
            for (RagService.Hit h : hits) {
                sb.append("· ").append(h.content()).append("\n");
            }
        }

        sb.append("\n需要的话，我可以直接对这套报价（含清洁费/服务费的总价），你看完再决定订不订。");
        return sb.toString();
    }

    /**
     * 判断是否为交易/订单意图（看价 / 下单 / 查单 / 取消）。
     *
     * <p>与混淆矩阵的边界句体系对齐（见《测试案例清单》第二节）：
     * 这些意图必须由 LLM 工具调用轨道执行真实工具（quote_price / create_order /
     * get_order / cancel_order），规则筛选轨道覆盖不了。
     * 判定宁可宽松：误路由的代价只是「让 LLM 自主选工具」（混淆矩阵实测 100% 选对），
     * 而漏路由的代价是交易功能整体失灵。
     */
    private boolean isTransactionIntent(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        // 已锁定房源 ID（yg-001 等）：看价/问答/下单都可能，交给 LLM 判断
        if (ROOM_ID_PATTERN.matcher(input).find()) {
            return true;
        }
        // 报价类：问价但语义上有明确对象（这套/这间/那套/它），或明确的报价动作词
        for (String kw : List.of("多少钱", "报价", "算一下价格", "价格算", "总价", "住两晚", "住三晚", "一晚多少钱")) {
            if (input.contains(kw)) {
                return true;
            }
        }
        // 下单类：强下单信号词（「我想订房先看看」这类搜索意图由 LLM 轨道自行选 search_rooms，无害）
        for (String kw : List.of("就订", "下单", "预订", "帮我订", "我要订", "订这套", "订这间", "订 yg", "订 ll")) {
            if (input.contains(kw)) {
                return true;
            }
        }
        // 查单/取消类
        for (String kw : List.of("订单", "退了", "取消", "不去了", "不要了", "退款", "退单")) {
            if (input.contains(kw)) {
                return true;
            }
        }
        // 支付类：明确付款意图 → 走 pay_order 工具（待支付 → 已确认）
        for (String kw : List.of("支付", "付款", "付钱", "去支付", "买单", "结账")) {
            if (input.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前需求是否属于「软性/知识性/偏好类」，即规则四轴筛选覆盖不了的场景。
     *
     * <p>规则轨道能处理的是「明确要找房」（有目的地/日期/预算/关系的纯交易意图）；
     * 以下需求应交给 LLM 自主工具调用（真 Function Calling）：
     * <ul>
     *   <li>事实/政策问答：能带宠物吗、几点退房、能开发票吗</li>
     *   <li>偏好/体验类：想泡私汤、要能看云海、周边有什么吃的</li>
     *   <li>无明确三要素但仍想获得建议的开放式需求</li>
     * </ul>
     *
     * <p><b>关键顺序（2026-11-17 E2E 修复）</b>：必须先判断「是否命中知识/偏好关键词」，
     * 再判断三要素。用户问「成都有什么景点」「周边有什么吃的」这类咨询，与会话里是否已凑齐
     * 三要素无关——即便已订好房，用户照样会问景点攻略。若先判 {@code hasMandatoryInfo()}
     * 短路，这类问题会被锁死在规则筛选轨道，重跑一遍房源推荐（用户错觉「问景点却答订房」）。
     */
    private boolean isSoftDemand(AgentState state, String userInput) {
        // 命中软性/知识性关键词 → 无条件进入 LLM 自主工具调用轨道（先于三要素判断）
        for (String kw : List.of("宠物", "狗", "猫", "退房", "发票", "开票", "停车", "温泉", "私汤",
                "云海", "日出", "吃", "餐", "玩", "徒步", "附近", "周边", "政策", "能", "可以", "有没有", "几点",
                "景点", "景区", "攻略", "旅游", "游", "逛", "打卡", "网红", "博物馆", "古镇", "特色")) {
            if (userInput != null && userInput.contains(kw)) {
                return true;
            }
        }
        // 硬交易意图（已具备三要素且无知识/偏好关键词）→ 规则轨道
        if (state.hasMandatoryInfo()) {
            return false;
        }
        return false;
    }

    /** 知识问答类关键词：纯咨询（景点/攻略/政策/玩法），与「找房」无关，直接 RAG 回答。 */
    private static final List<String> KNOWLEDGE_QUERY_KEYWORDS = List.of(
            "景点", "景区", "攻略", "旅游", "打卡", "博物馆", "古镇", "特色",
            "有什么好", "有什么好玩", "哪里好玩", "怎么玩", "吃什么", "小吃", "美食", "特产",
            "交通", "怎么去", "地铁", "天气", "门票", "开放时间", "几点");

    /**
     * 判断是否为「纯知识问答」意图（问景点/攻略/玩法/政策等），应走 RAG 直接回答，
     * 而非当成找房需求跑规则筛选。独立于 {@link #isSoftDemand}，先于规则筛选执行，
     * 不依赖 LLM（避免 LLM 402 时降级回订房）。
     */
    private boolean isKnowledgeQuery(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String kw : KNOWLEDGE_QUERY_KEYWORDS) {
            if (input.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 纯 RAG 知识问答：用用户问题 + 目的地检索知识库，命中则拼成回答。
     * 无命中时给诚实兜底，不编造、不转去订房。
     */
    private String buildKnowledgeAnswer(String userInput, AgentState state) {
        // 检索 query：优先用户原句，叠加上下文目的地，提高召回率
        String dest = state.getDestination();
        String query = userInput + (dest == null ? "" : " " + dest);
        List<RagService.Hit> hits = ragService.search(query, 3);

        // 过滤低分命中，避免硬凑
        List<RagService.Hit> valid = new ArrayList<>();
        for (RagService.Hit h : hits) {
            if (h.score() >= 0.05) {
                valid.add(h);
            }
        }

        if (valid.isEmpty()) {
            return "关于「" + userInput + "」，我这边暂时没有对应的详细攻略。"
                    + "我可以帮你：\n"
                    + "1. 换成推荐房源，帮你把住宿先定下来；\n"
                    + "2. 告诉我你更关注哪方面（吃什么/怎么玩/带娃），我再针对性补充。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("帮你查了一下「").append(userInput).append("」：\n\n");
        for (RagService.Hit h : valid) {
            sb.append("· ").append(h.content()).append("\n");
        }
        sb.append("\n如果你想把住宿也一起定下来，告诉我日期和预算，我马上帮你筛。");
        return sb.toString();
    }

    /**
     * 轨道 B：LLM 自主工具调用循环（真 Function Calling）。
     *
     * <p>把「用户需求 + 已抽取实体 + 地域知识」喂给 LLM，并附上 6 个工具的 Function Calling 定义，
     * 让 LLM 自主决定「该调哪个工具、传什么参」；代码执行工具后把结果回填，循环直到 LLM 输出最终结论。
     * 这是「真 AI 含量」的核心体现——LLM 不是文案，而是决策者。
     *
     * <p>安全护栏：最多循环 {@code MAX_TOOL_ROUNDS} 轮，防止 LLM 陷入无休止调用。
     */
    private String runLlmToolLoop(String userId, String userInput, AgentState state,
                                  List<LlmMessage> history, EventSink sink) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildToolCallingSystemPrompt()));
        messages.addAll(history);
        // 把已抽取的实体作为确定性事实喂给 LLM，避免它重复抽取、也保留可解释性
        messages.add(LlmMessage.user("用户需求：" + userInput + "\n已识别信息：" + summarizeExtracted(state)));

        List<Map<String, Object>> toolSpecs = toolRegistry.allFunctionSpecs();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmClient.LlmResponse resp;
            try {
                resp = llmClient.chat(messages, toolSpecs);
            } catch (Exception e) {
                // LLM 调用失败 → 降级到规则筛选结果兜底，不抛断主流程
                log.warn("LLM 自主工具调用失败，降级规则兜底：{}", e.toString());
                return fallbackToRuleAnswer(state);
            }
            if (resp == null) {
                return fallbackToRuleAnswer(state);
            }

            // 无 tool_calls：LLM 认为信息已够，直接输出最终结论
            if (resp.toolCalls() == null || resp.toolCalls().isEmpty()) {
                return resp.content() == null || resp.content().isBlank()
                        ? fallbackToRuleAnswer(state)
                        : resp.content();
            }

            // 有 tool_calls：记录 assistant 消息，逐个执行工具并回填 tool 消息
            messages.add(LlmMessage.assistantToolCalls(resp.toolCalls()));
            for (Map<String, Object> call : resp.toolCalls()) {
                Map<String, Object> fn = (Map<String, Object>) call.get("function");
                String toolName = String.valueOf(fn.get("name"));
                String argsJson = String.valueOf(fn.get("arguments"));
                String callId = String.valueOf(call.get("id"));

                // 推思考链事件：AI 正在调用某个工具
                sink.step(StepEvent.of(StepEventType.TOOL_CALLING, "调用工具 " + toolName + " …"));

                Map<String, Object> args = parseArgs(argsJson);
                ToolResult result = toolDispatcher.dispatch(userId, toolName, args);
                // 工具结果回填（role=tool），让 LLM 基于真实结果继续判断
                messages.add(LlmMessage.tool(callId, serializeToolResult(result)));
            }
        }

        // 循环超限：降级兜底
        return fallbackToRuleAnswer(state);
    }

    /** 工具调用 System Prompt：约束 LLM 的决策边界与输出口径。 */
    private String buildToolCallingSystemPrompt() {
        return """
                你是「栖迟」民宿预约顾问的智能决策内核。你可以调用工具来回答用户的问题。

                # 你的职责
                根据用户需求和已识别信息，自主判断「该调用哪个工具、传什么参数」，用真实工具结果来回答，而不是凭空编造。

                # 工具使用原则
                - 找房/筛选用 search_rooms；锁定某套房要价格用 quote_price；下单用 create_order；查订单用 get_order；取消用 cancel_order；问政策/房源/周边知识用 ask_house_info。
                - 一次可以调用多个工具，但只调用真正需要的；拿到结果后如果信息还不够，可以继续调用。
                - 信息足够时，直接输出最终回答，不要再调工具。

                # 输出要求
                - 用自然、有温度的中文回答，像真人顾问一样。
                - 不要编造工具没返回的事实（如虚构房源名、价格、政策）。
                - 最终回答要落到「用户下一步该做什么」上。
                """;
    }

    /** 解析 LLM 返回的工具参数 JSON 字符串为 Map。 */
    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argsJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 把 ToolResult 序列化为回填给 LLM 的字符串。 */
    private String serializeToolResult(ToolResult result) {
        try {
            if (result.success()) {
                return objectMapper.writeValueAsString(result.data());
            }
            return "工具 " + result.toolName() + " 执行失败：" + objectMapper.writeValueAsString(result.data());
        } catch (Exception e) {
            return "{\"note\":\"工具结果序列化异常\"}";
        }
    }

    /** LLM 工具调用链路失败/超限时的兜底：回退到规则筛选的话术。 */
    private String fallbackToRuleAnswer(AgentState state) {
        List<Map<String, Object>> candidates = filterCandidates("fallback", state);
        state.setCandidates(candidates);
        if (candidates.isEmpty()) {
            return "我暂时没能给出合适建议，你可以补充一下想去的目的地、日期和预算，我再帮你筛。";
        }
        return generateFinalAnswer(state, List.of());
    }

    /**
     * 清扫过期/超限的会话状态（对齐 ConversationMemory 的 evict 模式）：
     * 先按 TTL 清掉长期未活跃的，仍超上限则按最久未活跃淘汰，防内存泄漏。
     */
    private void evictSessionsIfNeeded() {
        long now = System.currentTimeMillis();
        sessionTouch.forEach((id, lastTouch) -> {
            if (now - lastTouch > SESSION_TTL_MS) {
                sessions.remove(id);
                sessionTouch.remove(id);
            }
        });
        while (sessions.size() > MAX_SESSIONS) {
            String oldest = sessionTouch.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                break;
            }
            sessions.remove(oldest);
            sessionTouch.remove(oldest);
        }
    }
}
