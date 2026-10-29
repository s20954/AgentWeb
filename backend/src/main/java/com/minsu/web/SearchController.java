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

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsu.rag.RagService;

/**
 * 智能检索接口 —— 「先查本地知识库，再联网补充，最后整合」。
 *
 * <p>检索策略（对齐用户预期）：
 * <ol>
 *   <li>先用 {@link RagService} 查本地知识库（莫干山片区/政策/房源/攻略）；</li>
 *   <li>若本地命中（最高分 ≥ 阈值），优先以本地知识为准，联网作为补充；</li>
 *   <li>若本地未命中，再联网搜索（DuckDuckGo 免 key），把网络结果带回；</li>
 *   <li>最终把「知识库 + 网络」两路结果整合返回，各自标注来源，供前端展示。</li>
 * </ol>
 *
 * <p>生产可替换联网服务（Bing/SerpAPI 等），接口签名不变。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final String DDG_URL = "https://api.duckduckgo.com/";
    /** 本地知识库「视为命中」的相似度阈值（0~1）。 */
    private static final double KB_HIT_THRESHOLD = 0.3;
    /** 本地知识库返回条数。 */
    private static final int KB_TOP_K = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RagService ragService;

    public SearchController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * 智能检索。
     *
     * <p>请求体：{@code {"query": "莫干山能带宠物吗"}}
     * 返回：{@code {"query","kbResults":[...],"webResults":[...],"answer","sources"}}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> search(@RequestBody(required = false) Map<String, Object> body) {
        String query = body == null ? null : String.valueOf(body.getOrDefault("query", ""));
        if (query == null || query.isBlank()) {
            return Map.of("error", "PARAM_INVALID", "message", "缺少 query 参数");
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
        try {
            String url = DDG_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&format=json&no_html=1&skip_disambig=1";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "MinsuAgent/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());
            webAnswer = root.path("AbstractText").asText("");
            for (JsonNode topic : root.path("RelatedTopics")) {
                if (topic.has("Topics")) {
                    for (JsonNode sub : topic.path("Topics")) {
                        webResults.add(toWebResult(sub));
                    }
                } else if (topic.has("Text")) {
                    webResults.add(toWebResult(topic));
                }
                if (webResults.size() >= 5) break;
            }
        } catch (Exception e) {
            webError = "联网搜索暂不可用：" + e.getMessage();
        }
        out.put("webResults", webResults);
        if (webError != null) {
            out.put("webError", webError);
        }

        // ---- 第 3 步：整合两路结果，组织最终回答 ----
        List<String> sources = new ArrayList<>();
        String answer = assembleAnswer(query, kbHit, kbResults, webAnswer, webResults, sources);
        out.put("answer", answer);
        out.put("sources", sources);
        // 标记本次结果主来源（kb / web / kb+web）
        out.put("primarySource", kbHit && webResults.isEmpty() ? "kb"
                : (!kbHit && !webResults.isEmpty()) ? "web" : "kb+web");

        // ---- 第 4 步：联网得到的有效内容沉淀回知识库（下次直接本地命中，无需再联网）----
        boolean sunk = sinkToKnowledgeBase(query, kbHit, webAnswer, webResults);
        out.put("sunkToKb", sunk);

        return out;
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
            if (t != null && !t.isBlank()) {
                if (content.length() > 0) content.append("；");
                content.append(t);
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

    private Map<String, Object> toWebResult(JsonNode node) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("title", node.path("Text").asText("").replaceAll("<[^>]+>", ""));
        r.put("url", node.path("FirstURL").asText(""));
        return r;
    }
}
