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

    private final RagService ragService;
    private final LlmClient llmClient;

    public SearchController(RagService ragService, LlmClient llmClient) {
        this.ragService = ragService;
        this.llmClient = llmClient;
    }

    /**
     * 智能检索。
     *
     * <p>请求体：{@code {"query": "莫干山能带宠物吗"}}
     * 返回：{@code {"query","kbResults":[...],"webResults":[...],"answer","sources"}}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> search(@RequestBody(required = false) Map<String, Object> body) {
        String query = body == null ? null : String.valueOf(body.getOrDefault("query", ""));
        if (query == null || query.isBlank()) {
            return Mono.just(Map.of("error", "PARAM_INVALID", "message", "缺少 query 参数"));
        }
        final String q = query;
        // 联网抓取与 LLM 调用都是阻塞 IO，WebFlux 事件循环上 block() 会直接抛异常，
        // 整体移到 boundedElastic 线程执行（与 ChatController 的虚拟线程思路一致）
        return Mono.fromCallable(() -> doSearch(q)).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> doSearch(String query) {

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
            String planned = planWithLlm(query, kbHit, kbResults, webAnswer, webResults, sources);
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

        return out;
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
        // 加上问题上下文，便于下次相似问句能检索到
        String kbContent = "关于「" + query + "」：联网了解到——" + content;
        String newId = ragService.addKnowledge("联网沉淀", kbContent);
        return newId != null;
    }

    /**
     * 把「用户需求 + 知识库 + 联网素材」喂给 LLM，走七步收敛思路，输出结构化可执行的规划方案。
     *
     * <p>这是「联网搜索」价值的核心：不再把搜索结果标题原样甩给用户，
     * 而是让 LLM 基于真实搜索到的内容自主思考、整理、规划，交付一份可直接使用的方案。
     *
     * <p>返回 {@code null} 表示规划失败（LLM 调用异常或返回为空），由调用方降级到字符串拼接。
     */
    private String planWithLlm(String query, boolean kbHit, List<Map<String, Object>> kbResults,
                               String webAnswer, List<Map<String, Object>> webResults,
                               List<String> sources) {
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
                - 若用户信息不全（没写日期/人数/预算），先按常见默认值规划并显式声明假设，不要反过来追问一堆问题。
                """;

        try {
            List<LlmMessage> messages = List.of(
                    LlmMessage.system(systemPrompt),
                    LlmMessage.user("我的需求：" + query + "\n\n以下是可供规划参考的素材：\n" + material));
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
