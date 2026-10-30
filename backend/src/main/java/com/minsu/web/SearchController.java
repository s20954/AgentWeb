package com.minsu.web;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minsu.agent.ConversationMemory;
import com.minsu.agent.llm.LlmClient;
import com.minsu.agent.llm.LlmMessage;
import com.minsu.rag.RagService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 智能检索接口 —— 「先查本地知识库，再联网补充，最后由 LLM 规划整理」。
 *
 * <p>检索策略（对齐用户预期）：
 * <ol>
 *   <li>先用 {@link RagService} 查本地知识库（莫干山片区/政策/房源/攻略）；</li>
 *   <li>若本地命中（最高分 ≥ 阈值），优先以本地知识为准，联网作为补充；</li>
 *   <li>若本地未命中，再联网搜索（必应），把网络结果带回；</li>
 *   <li>把「用户需求 + 知识库 + 网络」三路素材喂给 LLM，走七步收敛思路，输出一份结构化、可执行的规划方案；</li>
 *   <li>LLM 未配置时降级为「拼接字符串」，保证无 key 也能返回结果。</li>
 * </ol>
 *
 * <p>生产可替换联网服务（Bing/SerpAPI 等），接口签名不变。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    /** 常见国内目的地/城市白名单（含省、市、景区名），用于从口语中抽取搜索关键词。 */
    private static final List<String> DESTINATIONS = List.of(
            "莫干山", "成都", "重庆", "北京", "上海", "广州", "深圳", "杭州", "苏州", "南京",
            "西安", "武汉", "长沙", "厦门", "青岛", "大理", "丽江", "三亚", "桂林", "张家界",
            "黄山", "泰山", "九寨沟", "稻城", "香格里拉", "西双版纳", "腾冲", "北海", "涠洲岛",
            "长白山", "呼伦贝尔", "阿勒泰", "新疆", "西藏", "青海", "甘肃", "宁夏", "内蒙",
            "云南", "贵州", "四川", "广西", "海南", "福建", "江西", "安徽", "浙江", "江苏");

    private static final String DDG_URL = "https://api.duckduckgo.com/";
    /** 联网搜索源：必应（国内可直连，无需 key，反爬宽松）。 */
    private static final String BING_URL = "https://cn.bing.com/search";
    /** 本地知识库「视为命中」的相似度阈值（0~1）。 */
    private static final double KB_HIT_THRESHOLD = 0.3;
    /** 本地知识库返回条数。 */
    private static final int KB_TOP_K = 3;
    /** 联网返回条数上限。 */
    private static final int WEB_MAX = 5;

    /** 追问挂起的会话存活时间（10 分钟）。 */
    private static final long CLARIFY_TTL_MS = 10 * 60 * 1000L;
    /** 挂起追问的会话上限：不设上限且过期条目只在同 sessionId 再访问时清理，会被刷量打爆内存。 */
    private static final int MAX_PENDING_CLARIFICATIONS = 1000;

    /** 人数/同行人群线索（俩人、情侣、亲子、朋友、独自…）。 */
    private static final Pattern PEOPLE_HINT = Pattern.compile(
            "(俩|两|仨|四|五|六|七|八|九|十|\\d+)\\s*(人|位|口)|情侣|夫妻|爱人|对象|亲子|带娃|孩子|宝宝|朋友|闺蜜|兄弟|家人|家庭|父母|爸妈|独自|一个人|同事|同学|团建");
    /** 行程天数线索（3天、两晚、周末、五一…）。 */
    private static final Pattern DAYS_HINT = Pattern.compile(
            "[一二三四五六七八九十\\d]+\\s*(天|晚|夜)|\\d+\\s*天\\s*\\d+\\s*晚|周末|小长假|五一|十一|国庆|春节|暑假|寒假");
    /** 预算线索（预算xxx、每晚800、人均500…）。 */
    private static final Pattern BUDGET_HINT = Pattern.compile(
            "预算|\\d+\\s*(元|块|千|k|K)|每晚|人均");
    /** 规划意图线索（「能带宠物吗」这类事实问答不触发缺口追问，直接答）。 */
    private static final Pattern PLANNING_HINT = Pattern.compile(
            "规划|计划|安排|攻略|行程|路线|怎么玩|去哪|游玩|旅游|度假");

    /** 会话挂起：sessionId → 原始需求（等待用户补充关键信息）。 */
    private final Map<String, PendingQuery> pendingClarifications = new java.util.concurrent.ConcurrentHashMap<>();

    /** 一次被挂起的需求：原始问题 + 过期时间。 */
    private record PendingQuery(String originalQuery, long expireAt) {
    }

    private final RagService ragService;
    private final LlmClient llmClient;
    private final ConversationMemory conversationMemory;

    public SearchController(RagService ragService, LlmClient llmClient, ConversationMemory conversationMemory) {
        this.ragService = ragService;
        this.llmClient = llmClient;
        this.conversationMemory = conversationMemory;
    }

    /**
     * 智能检索。
     *
     * <p>请求体：{@code {"query": "莫干山能带宠物吗"}}
     * 返回：{@code {"query","kbResults":[...],"webResults":[...],"answer","sources"}}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> search(@RequestBody(required = false) Map<String, Object> body) {
        // 注意不能用 getOrDefault("query","")：客户端传 {"query":null} 时 key 存在、值为 null，
        // getOrDefault 不兜底，String.valueOf(null) 会变成字符串 "null" 继续走检索
        Object rawQuery = body == null ? null : body.get("query");
        String query = rawQuery == null ? null : String.valueOf(rawQuery);
        if (query == null || query.isBlank()) {
            return Mono.just(Map.of("error", "PARAM_INVALID", "message", "缺少 query 参数"));
        }
        // 会话 ID：用于「追问挂起 → 用户补充 → 合并规划」的多轮衔接
        String sessionId = body == null || body.get("sessionId") == null
                ? "default" : String.valueOf(body.get("sessionId"));
        final String q = query;
        // 联网抓取与 LLM 调用都是阻塞 IO，WebFlux 事件循环上 block() 会直接抛异常，
        // 整体移到 boundedElastic 线程执行（与 ChatController 的虚拟线程思路一致）
        return Mono.fromCallable(() -> doSearch(q, sessionId)).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> doSearch(String query, String sessionId) {

        // 先清扫过期/超限的挂起追问，防内存泄漏
        evictPendingClarificationsIfNeeded();
        // 用户本轮的原始输入（用于写入对话记忆；query 可能被追问合并改写，不改写记忆里的原文）
        final String rawQuery = query;
        // ---- 对话记忆：取出本会话之前的问答历史，让本轮回答带着上下文（不再每句从零开始）----
        final List<LlmMessage> history = conversationMemory.recent(sessionId);

        // ---- 第 0 步：若上一轮在等用户补充信息，把补充合并进原始需求 ----
        query = mergePendingClarification(query, sessionId);

        // ---- 缺口识别：规划类需求且关键信息不全时先追问，不急着规划（宁可问清楚，不瞎猜默认值）----
        // 事实问答（能带宠物吗/门票多少）不走追问，直接检索回答
        List<String> gaps = PLANNING_HINT.matcher(query).find() ? findGaps(query) : List.of();
        if (!gaps.isEmpty()) {
            pendingClarifications.put(sessionId,
                    new PendingQuery(query, System.currentTimeMillis() + CLARIFY_TTL_MS));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            String clarifyAnswer = buildClarifyQuestion(gaps);
            out.put("answer", clarifyAnswer);
            out.put("sources", List.of("待补充信息"));
            out.put("needClarification", true);
            out.put("missingFields", gaps);
            out.put("primarySource", "clarify");
            // 追问也是一轮对话，记入记忆——后续回答要知道「我之前问过什么」
            conversationMemory.appendTurn(sessionId, rawQuery, clarifyAnswer);
            return out;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);

        // ---- 第 1 步：先查本地知识库 ----
        List<RagService.Hit> hits = ragService.search(query, KB_TOP_K);
        boolean kbHit = !hits.isEmpty() && hits.get(0).score() >= KB_HIT_THRESHOLD;

        List<Map<String, Object>> kbResults = new ArrayList<>();
        for (RagService.Hit h : hits) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("category", h.category());
            r.put("content", h.content());
            r.put("score", Math.round(h.score() * 100) / 100.0);
            kbResults.add(r);
        }
        out.put("kbResults", kbResults);

        // ---- 第 2 步：联网搜索（本地命中则作补充，未命中则作为主结果）----
        List<Map<String, Object>> webResults = new ArrayList<>();
        String webAnswer = null;
        String webError = null;
        // 关键修复：不要用整句口语去搜，先抽「搜索关键词」，否则「我想去成都玩两天」会被
        // 必应当成搜「我」字，返回一堆无关的百科/歌曲/游戏。
        String searchQuery = buildSearchQuery(query);
        try {
            String url = BING_URL + "?q=" + URLEncoder.encode(searchQuery, StandardCharsets.UTF_8)
                    + "&setlang=zh-CN&count=" + WEB_MAX;
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String html = resp.body();

            // 解析必应结果：每个结果块 = <h2><a href="URL">标题</a></h2><div class="b_caption"><p>摘要</p></div>
            Pattern block = Pattern.compile(
                    "<h2[^>]*>\\s*<a[^>]*href=\"(http[^\"]+)\"[^>]*>(.*?)</a>\\s*</h2>\\s*<div class=\"b_caption\"[^>]*>\\s*<p[^>]*>(.*?)</p>",
                    Pattern.DOTALL);
            Matcher m = block.matcher(html);
            int count = 0;
            StringBuilder answerBuilder = new StringBuilder();
            while (m.find() && count < WEB_MAX) {
                String link = m.group(1);
                String title = stripTags(m.group(2)).trim();
                String snippet = stripTags(m.group(3)).trim();
                if (title.isBlank()) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("title", title);
                r.put("url", link);
                r.put("snippet", snippet);
                webResults.add(r);
                // 用标题 + 摘要拼一段可读的「网络摘要」，作为 answer 的素材
                if (count == 0 && !snippet.isBlank()) {
                    answerBuilder.append(snippet);
                }
                count++;
            }
            if (!webResults.isEmpty() && answerBuilder.length() > 0) {
                webAnswer = answerBuilder.toString();
            }
        } catch (Exception e) {
            webError = "联网搜索暂不可用：" + e.getMessage();
        }
        out.put("webResults", webResults);
        if (webError != null) {
            out.put("webError", webError);
        }

        // ---- 第 3 步：整合两路结果，组织最终回答 ----
        // 优先：把「需求 + 知识库 + 联网素材」喂给 LLM，走七步收敛输出结构化规划方案；
        // 降级：LLM 未配置（无 key）或调用失败时，用拼接字符串兜底，保证仍能返回可读结果。
        List<String> sources = new ArrayList<>();
        String answer;
        boolean llmPlanned = false; // 本次是否真正由 LLM 产出了规划（决定后续是否允许沉淀）
        if (llmClient.isConfigured()) {
            String planned = planWithLlm(query, kbHit, kbResults, webAnswer, webResults, sources, history);
            if (planned != null) {
                answer = planned;
                llmPlanned = true;
            } else {
                answer = assembleAnswer(query, kbHit, kbResults, webAnswer, webResults, sources);
            }
        } else {
            answer = assembleAnswer(query, kbHit, kbResults, webAnswer, webResults, sources);
        }
        out.put("answer", answer);
        out.put("sources", sources);
        // 标记本次结果主来源（kb / web / kb+web）
        out.put("primarySource", kbHit && webResults.isEmpty() ? "kb"
                : (!kbHit && !webResults.isEmpty()) ? "web" : "kb+web");

        // ---- 第 4 步：联网得到的有效内容沉淀回知识库（下次直接本地命中，无需再联网）----
        // 只有「LLM 规划成功」才沉淀，降级拼接的原始搜索结果（可能含百科/字典类噪音）不沉淀，避免污染知识库。
        boolean sunk = llmPlanned && sinkToKnowledgeBase(query, kbHit, webAnswer, webResults);
        out.put("sunkToKb", sunk);

        // ---- 第 5 步：本轮问答写入对话记忆，供下一轮延续上下文 ----
        conversationMemory.appendTurn(sessionId, rawQuery, answer);

        return out;
    }

    /**
     * 若该会话上一轮在等用户补充信息，把本次输入合并进原始需求（并清除挂起）。
     * 过期（10 分钟）则丢弃挂起，本次输入按新需求处理。
     */
    private String mergePendingClarification(String query, String sessionId) {
        PendingQuery pending = pendingClarifications.remove(sessionId);
        if (pending == null) {
            return query;
        }
        if (pending.expireAt() < System.currentTimeMillis()) {
            return query; // 已过期，按新需求处理
        }
        return pending.originalQuery() + "。补充信息：" + query;
    }

    /** 清扫过期/超限的挂起追问：先按过期时间清，仍超上限则按最早过期淘汰（对齐 ConversationMemory 的 evict 模式）。 */
    private void evictPendingClarificationsIfNeeded() {
        long now = System.currentTimeMillis();
        pendingClarifications.entrySet().removeIf(e -> e.getValue().expireAt() < now);
        while (pendingClarifications.size() > MAX_PENDING_CLARIFICATIONS) {
            String oldest = pendingClarifications.entrySet().stream()
                    .min((a, b) -> Long.compare(a.getValue().expireAt(), b.getValue().expireAt()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                break;
            }
            pendingClarifications.remove(oldest);
        }
    }

    /**
     * 识别规划必需的关键信息缺口：人数/同行人、行程天数、（可选）预算、目的地。
     * 返回缺口标签列表；为空表示信息齐全、可以直接规划。
     */
    private List<String> findGaps(String query) {
        List<String> gaps = new ArrayList<>();
        boolean hasDest = DESTINATIONS.stream().anyMatch(query::contains);
        if (!hasDest) {
            gaps.add("目的地");
        }
        if (!PEOPLE_HINT.matcher(query).find()) {
            gaps.add("人数/同行人");
        }
        if (!DAYS_HINT.matcher(query).find()) {
            gaps.add("行程天数");
        }
        // 预算只问不拦：缺预算不阻塞规划，仅在追问里顺带一问
        return gaps;
    }

    /** 组装追问话术：只问缺失的项，预算作为可选项顺带提及。 */
    private String buildClarifyQuestion(List<String> gaps) {
        StringBuilder sb = new StringBuilder();
        sb.append("为了给你做一份能直接照着执行的规划，我还需要确认几个关键信息：\n");
        int i = 1;
        for (String gap : gaps) {
            switch (gap) {
                case "目的地" -> sb.append(i++).append(". **想去哪里？**（目的地城市/景区）\n");
                case "人数/同行人" -> sb.append(i++).append(". **几个人去？和谁？**（情侣/朋友/家人/带娃…，住宿安排差别很大）\n");
                case "行程天数" -> sb.append(i++).append(". **计划玩几天？** 大概什么时候出发？\n");
                default -> { }
            }
        }
        sb.append(i).append(". （可选）**预算**大概多少？比如每晚住宿上限或人均总预算。\n");
        sb.append("\n回复我这些信息后，我马上给你出完整方案。");
        return sb.toString();
    }

    /**
     * 把用户的整句口语输入清洗成「搜索关键词」，避免把「我/想/去/你们」这类虚词当作搜索词。
     *
     * <p>策略：
     * <ol>
     *   <li>先抽目的地（白名单地名），没有则回退到整句；</li>
     *   <li>去掉常见口语虚词与标点；</li>
     *   <li>保留「攻略/路线/景点/住宿」等意图词，不足则补「旅游攻略」；</li>
     *   <li>若一句话里有明确的「几天/几晚」，补「N天」。</li>
     * </ol>
     */
    private String buildSearchQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;

        // 1) 抽目的地
        String dest = null;
        for (String d : DESTINATIONS) {
            if (s.contains(d)) {
                dest = d;
                break;
            }
        }

        // 2) 去掉口语虚词与代词（这些词对搜索无意义，还会污染关键词）
        String cleaned = s.replaceAll(
                "[，。！？、,.!?;；:：\"'“”‘’（）()\\[\\]【】\\s]+", " ");
        for (String junk : List.of("我来", "你来", "请你", "帮我", "麻烦", "我想", "我要", "我打算", "我计划",
                "我们去", "我要去", "想去", "打算去", "计划去", "进行规划", "规划一下", "给我", "你们",
                "我俩", "两个人", "俩人", "两人", "二人", "几位", "玩", "游玩", "游", "一下", "好吗", "可以吗")) {
            cleaned = cleaned.replace(junk, " ");
        }
        // 去掉残留的单个「我/你/去/想/要」字（避免成关键词）
        cleaned = cleaned.replaceAll("(?<![\\u4e00-\\u9fa5])(?:我|你|他|她|它)(?![\\u4e00-\\u9fa5])", " ");

        // 3) 拆词、去空、去重（保持顺序）
        List<String> tokens = new ArrayList<>();
        for (String t : cleaned.split("\\s+")) {
            t = t.trim();
            if (t.isEmpty() || t.length() == 1 && !DESTINATIONS.contains(t)) {
                continue;
            }
            if (!tokens.contains(t)) {
                tokens.add(t);
            }
        }

        // 4) 组装：目的地打头，补「旅游攻略」后缀
        StringBuilder sb = new StringBuilder();
        if (dest != null) {
            sb.append(dest);
        }
        for (String t : tokens) {
            if (dest != null && t.equals(dest)) {
                continue; // 目的地已在开头
            }
            sb.append(" ").append(t);
        }
        String result = sb.toString().trim();

        // 5) 若结果太单薄（只有目的地或为空），补「旅游攻略」兜底
        if (result.isEmpty() || result.equals(dest) || result.split("\\s+").length < 2) {
            if (dest != null) {
                result = dest + " 旅游攻略";
            } else {
                result = raw;
            }
        }
        return result;
    }

    /** 把「联网得到、且本地原先没有」的有效知识沉淀回知识库。 */
    private boolean sinkToKnowledgeBase(String query, boolean kbHit,
                                        String webAnswer, List<Map<String, Object>> webResults) {
        // 只有「本地未命中、联网确实搜到了内容」才沉淀；本地已命中则不重复写
        if (kbHit) {
            return false;
        }
        StringBuilder content = new StringBuilder();
        if (webAnswer != null && !webAnswer.isBlank()) {
            content.append(webAnswer);
        }
        for (Map<String, Object> w : webResults) {
            String t = String.valueOf(w.get("title"));
            if (t != null && !t.isBlank() && !"null".equals(t)) {
                if (content.length() > 0) content.append("；");
                content.append(t);
                // 摘要更完整，优先作为沉淀内容
                Object sn = w.get("snippet");
                if (sn != null && !String.valueOf(sn).isBlank() && !"null".equals(String.valueOf(sn))) {
                    content.append("——").append(sn);
                }
            }
        }
        if (content.length() == 0) {
            return false;
        }
        // 相关性校验：必应经常对口语化/指代类查询返回完全无关的结果（字典释义、股票行情…），
        // 不校验就沉淀会把垃圾写进知识库污染后续检索（实测出现过「成都游玩」沉淀出股票行情）。
        if (!isWebContentRelevant(query, content.toString())) {
            log.info("联网结果与问题不相关，跳过沉淀：query={}", query);
            return false;
        }
        // 加上问题上下文，便于下次相似问句能检索到
        String kbContent = "关于「" + query + "」：联网了解到——" + content;
        String newId = ragService.addKnowledge("联网沉淀", kbContent);
        return newId != null;
    }

    /**
     * 校验联网内容是否与问题相关（沉淀前的防污染闸门）。
     *
     * <p>规则：问题里含白名单目的地时，要求联网内容至少提到该目的地；
     * 无目的地（多为「那带小孩的呢」这类依赖上下文的追问）时，要求问题中任一 ≥2 字的内容词
     * 出现在联网内容里。都不满足视为无关。
     */
    private boolean isWebContentRelevant(String query, String webContent) {
        if (webContent == null || webContent.isBlank()) {
            return false;
        }
        for (String d : DESTINATIONS) {
            if (query.contains(d)) {
                return webContent.contains(d);
            }
        }
        // 无目的地：取问题中 ≥2 字的连续中文段（去掉通用词），任一命中即算相关
        for (String seg : query.split("[^\\u4e00-\\u9fa5]+")) {
            if (seg.length() < 2) {
                continue;
            }
            for (String generic : List.of("旅游", "攻略", "怎么", "如何", "推荐", "一下", "帮我", "我想", "我要")) {
                seg = seg.replace(generic, " ");
            }
            for (String token : seg.split("\\s+")) {
                if (token.length() >= 2 && webContent.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 把「用户需求 + 知识库 + 联网素材」喂给 LLM，走七步收敛思路，输出结构化可执行的规划方案。
     *
     * <p>这是「联网搜索」价值的核心：不再把搜索结果标题原样甩给用户，
     * 而是让 LLM 基于真实搜索到的内容自主思考、整理、规划，交付一份可直接使用的方案。
     *
     * <p>返回 {@code null} 表示规划失败（LLM 调用异常或返回为空），由调用方降级到字符串拼接。
     *
     * @param history 本会话之前的问答历史（user/assistant 交替），拼在 system 之后、当前需求之前，
     *                让本轮回答记得「用户之前问过什么、我之前答过什么」
     */
    private String planWithLlm(String query, boolean kbHit, List<Map<String, Object>> kbResults,
                               String webAnswer, List<Map<String, Object>> webResults,
                               List<String> sources, List<LlmMessage> history) {
        // 组装素材
        StringBuilder material = new StringBuilder();
        if (kbHit && !kbResults.isEmpty()) {
            material.append("【本地知识库】\n");
            for (Map<String, Object> r : kbResults) {
                material.append("- [").append(r.get("category")).append("] ").append(r.get("content")).append("\n");
            }
        }
        if (webAnswer != null && !webAnswer.isBlank()) {
            material.append("【联网摘要】\n").append(webAnswer).append("\n");
        }
        if (!webResults.isEmpty()) {
            material.append("【联网结果】\n");
            for (Map<String, Object> w : webResults) {
                material.append("- ").append(w.get("title"));
                if (w.get("snippet") != null && !String.valueOf(w.get("snippet")).isBlank()) {
                    material.append("：").append(w.get("snippet"));
                }
                material.append("\n");
            }
        }
        if (material.length() == 0) {
            // 没有任何素材，让 LLM 基于自身知识直接规划（并提示可联网补充）
            material.append("（本次未检索到额外的本地或联网素材，请基于你已有的知识进行规划）");
        }

        String systemPrompt = """
                你是「替用户做减法的旅行规划顾问」。用户带来一句话需求，你要交付一份结构化、可执行的规划方案。

                # 核心原则
                - 你不是搜索引擎，不要把搜索结果标题和链接原样甩给用户。
                - 你要基于下方提供的【素材】做自主思考、整理、规划，输出一份能直接照着执行的方案。
                - 若素材不足以支撑完整规划，可以结合你已有的知识补充，但要诚实标注哪些是「素材中查到的」、哪些是「通用建议」。
                - 禁止胡编乱造具体事实（如虚构的酒店名、精确的价格、具体餐厅电话）。不确定就写「建议到当地再确认」。

                # 输出结构（用 Markdown，层次清晰）
                1. **行程概览**：一句话总结 + 天数/人数/目的地。
                2. **每日安排**：按 Day1、Day2… 列出，每天包含「上午 / 下午 / 晚上」的关键安排与理由。
                3. **住宿建议**：住哪个片区、为什么、大致价位。
                4. **美食/体验清单**：推荐吃什么、体验什么（来自素材则优先采用）。
                5. **交通与注意事项**：怎么去、当地交通、需要提前准备/预约的事项。
                6. **一句话结论**：给用户一个明确的下一步动作。

                # 要求
                - 语言自然、口语化、可直接读。
                - 不要输出你的思考过程，直接输出方案本身。
                - 你正在和用户进行一场连续对话，对话历史附在下方。请带着历史回答：
                  用户之前问过什么、你之前答过什么（包括之前没答上来的），都是本轮回答的上下文；
                  用户说「那里/它/刚才说的」这类指代时，从历史中还原所指，不要把本轮当成孤立的新问题。
                - 关键信息（目的地/人数/天数）已在追问环节与用户确认，直接按确认的信息规划；
                  若仍有个别细节缺失（如具体预算），按最常见情况处理并一句话说明假设，不要再反问。
                """;

        try {
            List<LlmMessage> messages = new ArrayList<>();
            messages.add(LlmMessage.system(systemPrompt));
            messages.addAll(history);
            messages.add(LlmMessage.user("我的需求：" + query + "\n\n以下是可供规划参考的素材：\n" + material));
            LlmClient.LlmResponse resp = llmClient.chat(messages, List.of());
            if (resp != null && resp.content() != null && !resp.content().isBlank()) {
                sources.add("AI 规划整理");
                return resp.content();
            }
        } catch (Exception e) {
            // LLM 调用失败 → 返回 null，由调用方降级到字符串拼接；日志必须留痕，别再静默
            log.warn("LLM 规划失败，降级为拼接返回：{}", e.toString());
        }
        // 规划失败（调用异常 / 返回为空），返回 null 交由调用方降级，不在此处拼接
        return null;
    }

    /** 整合知识库 + 网络结果，组织一段自然语言回答。 */
    private String assembleAnswer(String query, boolean kbHit,
                                  List<Map<String, Object>> kbResults,
                                  String webAnswer, List<Map<String, Object>> webResults,
                                  List<String> sources) {
        StringBuilder sb = new StringBuilder();

        if (kbHit) {
            // 本地知识库命中 → 以本地为准
            sources.add("本地知识库");
            sb.append("根据本地知识库：\n");
            for (Map<String, Object> r : kbResults) {
                sb.append("· [").append(r.get("category")).append("] ").append(r.get("content")).append("\n");
            }
            // 联网结果作为补充（如有）
            if (!webResults.isEmpty() || (webAnswer != null && !webAnswer.isBlank())) {
                sources.add("联网补充");
                sb.append("\n联网补充：\n");
                if (webAnswer != null && !webAnswer.isBlank()) {
                    sb.append(webAnswer).append("\n");
                }
                for (Map<String, Object> w : webResults) {
                    sb.append("- ").append(w.get("title")).append("\n");
                }
            }
        } else {
            // 本地未命中 → 以联网为准
            sources.add("联网搜索");
            if (webAnswer != null && !webAnswer.isBlank()) {
                sb.append(webAnswer).append("\n");
            }
            if (!webResults.isEmpty()) {
                sb.append("相关结果：\n");
                for (Map<String, Object> w : webResults) {
                    sb.append("- ").append(w.get("title"));
                    if (w.get("url") != null && !String.valueOf(w.get("url")).isBlank()) {
                        sb.append("\n  ").append(w.get("url"));
                    }
                    sb.append("\n");
                }
            }
            if ((webAnswer == null || webAnswer.isBlank()) && webResults.isEmpty()) {
                sb.append("本地知识库与联网均未找到相关信息，建议换个关键词或补充更具体的问题。");
            }
        }
        return sb.toString();
    }

    /** 去掉 HTML 标签与实体，得到纯文本。 */
    private String stripTags(String html) {
        if (html == null) {
            return "";
        }
        String s = html.replaceAll("<[^>]+>", "");
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replace("&ensp;", " ").replace("&#0183;", "·");
        return s.trim();
    }
}
