package com.minsu.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * RAG 检索服务 —— 纯内存余弦相似度检索（不引入 SQLite-VSS / Milvus 等重依赖）。
 *
 * <p>检索策略：
 * <ol>
 *   <li>分词：中文按「单字 unigram + 相邻二字 bigram」切分，英文/数字按词切分；</li>
 *   <li>向量：每条 doc 与 query 用词频 TF 建向量，算余弦相似度（0~1）；</li>
 *   <li>关键词加权：命中领域关键词（片区/风格/政策/攻略词）额外加分，保证短问句也能召回到位；</li>
 *   <li>返回 Top-K 条 + 每条 score，供前端展示检索来源、增强可解释性。</li>
 * </ol>
 *
 * <p>生产可换 SQLite-VSS / Milvus / pgvector，仅替换本类实现，对外 {@link #search(String, int)} 接口不变。
 */
@Component
public class RagService {

    /** 一条检索命中结果。 */
    public record Hit(String id, String category, String content, double score) {
    }

    /** 领域关键词（用于命中加权，覆盖片区/风格/政策/攻略）。 */
    private static final Set<String> KEYWORDS = Set.of(
            "莫干山", "庾村", "劳岭", "仙潭", "后坞", "筏头", "南路",
            "亲子", "孩子", "带娃", "儿童", "家庭", "情侣", "朋友",
            "宠物", "狗", "猫", "退房", "发票", "开票", "停车",
            "徒步", "餐饮", "农家菜", "咖啡", "早餐",
            "设计", "原木", "网红", "竹海", "溪流", "现代",
            "玻璃屋", "私汤", "云海", "玩水", "游乐");

    /** 单个关键词命中加分。 */
    private static final double KEYWORD_BONUS = 0.2;
    /** 关键词加权封顶。 */
    private static final double KEYWORD_CAP = 0.6;

    private final KbStore kbStore;

    public RagService(KbStore kbStore) {
        this.kbStore = kbStore;
    }

    /**
     * 检索与 query 最相关的 Top-K 条知识文档。
     *
     * <p>每次实时读取知识库最新文档（支持「联网结果动态沉淀」后即时可检索）。
     *
     * @param query 用户自然语言问题
     * @param topK  返回条数（默认 3）
     * @return 按相似度降序的命中列表（分数 0~1；无任何重叠时返回空）
     */
    public List<Hit> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, Integer> qv = tf(tokenize(query));
        List<KbDocument> docs = kbStore.documents();

        List<Hit> hits = new ArrayList<>();
        for (KbDocument doc : docs) {
            double cosine = cosine(qv, tf(tokenize(doc.content())));
            double boost = keywordBoost(query, doc.content());
            double score = Math.min(1.0, cosine + boost);
            if (score > 0) {
                hits.add(new Hit(doc.id(), doc.category(), doc.content(), score));
            }
        }

        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > topK ? hits.subList(0, topK) : hits;
    }

    /**
     * 把一条新知识沉淀进知识库（联网结果回写）。
     *
     * @param category 分类
     * @param content  内容
     * @return 新文档 ID；内容已存在（去重）则返回 {@code null}
     */
    public String addKnowledge(String category, String content) {
        return kbStore.addDocument(null, category, content);
    }

    // ---------------------------------------------------------------- 分词 / 向量

    /** 中文 unigram + bigram，英文/数字按整词切分。 */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                // 连续英文/数字作为整词（小写归一）
                StringBuilder word = new StringBuilder();
                while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) {
                    word.append(Character.toLowerCase(text.charAt(i)));
                    i++;
                }
                i--;
                tokens.add(word.toString());
            }
        }
        String s = cjk.toString();
        for (int i = 0; i < s.length(); i++) {
            tokens.add(String.valueOf(s.charAt(i)));        // unigram
            if (i + 1 < s.length()) {
                tokens.add(s.substring(i, i + 2));           // bigram
            }
        }
        return tokens;
    }

    private boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    /** 词频统计。 */
    private Map<String, Integer> tf(List<String> tokens) {
        Map<String, Integer> freq = new HashMap<>();
        for (String t : tokens) {
            freq.merge(t, 1, Integer::sum);
        }
        return freq;
    }

    /** 两向量余弦相似度（TF 向量，非负，结果 0~1）。 */
    private double cosine(Map<String, Integer> a, Map<String, Integer> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            Integer bc = b.get(e.getKey());
            if (bc != null) {
                dot += (double) e.getValue() * bc;
            }
            na += (double) e.getValue() * e.getValue();
        }
        double nb = 0;
        for (int c : b.values()) {
            nb += (double) c * c;
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 领域关键词命中加权：query 与 doc 同时命中同一关键词则加分（封顶）。 */
    private double keywordBoost(String query, String docContent) {
        String q = query.toLowerCase(Locale.ROOT);
        String d = docContent.toLowerCase(Locale.ROOT);
        double boost = 0;
        for (String kw : KEYWORDS) {
            if (q.contains(kw) && d.contains(kw)) {
                boost += KEYWORD_BONUS;
                if (boost >= KEYWORD_CAP) {
                    return KEYWORD_CAP;
                }
            }
        }
        return boost;
    }
}
