package com.minsu.rag;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 检索服务测试 —— 纯内存余弦相似度 + 关键词加权。
 *
 * <p>验收点：亲子意图召回筏头/亲子文档、宠物意图命中政策FAQ、分数 0~1 且排序正确。
 */
class RagServiceTest {

    private RagService ragService;

    @BeforeEach
    void setUp() {
        // 用临时目录的 SQLite 文件，避免测试污染真实 data/kb.db
        String tmpDb = System.getProperty("java.io.tmpdir") + "/minsu-kb-test-" + System.nanoTime() + ".db";
        KbRepository repo = new KbRepository(tmpDb);
        ragService = new RagService(new KbStore(repo));
    }

    @Test
    void familyQueryRetrievesFatouOrFamilyDoc() {
        List<RagService.Hit> hits = ragService.search("莫干山哪个片区适合亲子", 3);

        assertFalse(hits.isEmpty(), "亲子查询应能召回知识文档");
        boolean found = hits.stream().anyMatch(h ->
                h.content().contains("筏头") || h.content().contains("亲子"));
        assertTrue(found, "Top-K 中应包含筏头/亲子相关文档");
    }

    @Test
    void petQueryHitsPolicyFaq() {
        List<RagService.Hit> hits = ragService.search("可以带宠物吗", 3);

        assertFalse(hits.isEmpty(), "宠物查询应能召回知识文档");
        assertEquals("政策FAQ", hits.get(0).category(), "宠物问题应命中政策FAQ类目");
        assertTrue(hits.get(0).content().contains("宠物"), "命中文档应包含宠物相关描述");
    }

    @Test
    void scoresInRangeAndSorted() {
        List<RagService.Hit> hits = ragService.search("莫干山哪个片区适合亲子", 3);

        assertFalse(hits.isEmpty());
        for (RagService.Hit h : hits) {
            assertTrue(h.score() >= 0 && h.score() <= 1.0, "相似度分数应在 0~1 之间，实际=" + h.score());
        }
        for (int i = 0; i < hits.size() - 1; i++) {
            assertTrue(hits.get(i).score() >= hits.get(i + 1).score(), "Top-K 应按分数降序排列");
        }
    }

    // ---------------------------------------------------------------- 联网结果沉淀回知识库

    @Test
    void addKnowledgeThenRetrieveLocally() {
        // 起初：一个知识库里没有的冷门问题（如「红叶季」），检索不到相关内容
        List<RagService.Hit> before = ragService.search("莫干山红叶最佳观赏期", 3);
        boolean beforeHasRedLeaf = before.stream().anyMatch(h -> h.content().contains("红叶"));

        // 模拟「联网查到结果后沉淀」：把整理好的知识写回知识库
        String newId = ragService.addKnowledge("联网沉淀",
                "关于「莫干山红叶」：联网了解到——莫干山红叶最佳观赏期为 11 月中旬到 12 月初，劳岭仙潭与后坞沿线最集中。");
        assertTrue(newId != null && !newId.isBlank(), "新知识应成功写入并返回 ID");

        // 之后：同样的问题应能直接从本地知识库命中，无需再联网
        List<RagService.Hit> after = ragService.search("莫干山红叶最佳观赏期", 3);
        boolean afterHasRedLeaf = after.stream().anyMatch(h -> h.content().contains("红叶"));
        assertTrue(afterHasRedLeaf, "沉淀后的知识应能被本地检索命中");

        if (!beforeHasRedLeaf) {
            // 仅当之前确实没有时，验证「从无到有」的沉淀效果
            assertTrue(afterHasRedLeaf, "冷门问题沉淀后应可本地命中");
        }
    }

    @Test
    void addKnowledgeDeduplicatesSameContent() {
        String content = "关于「莫干山停车」：联网补充——各片区民宿多提供免费停车，后坞车位紧张需提前确认。";
        String id1 = ragService.addKnowledge("联网沉淀", content);
        assertTrue(id1 != null, "首次写入应成功");

        String id2 = ragService.addKnowledge("联网沉淀", content);
        assertEquals(null, id2, "相同内容不应重复写入（去重）");
    }
}
