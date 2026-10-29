package com.minsu.rag;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * RAG 知识库存储 —— 内置 15 条莫干山中文知识文档，并支持「联网结果沉淀回知识库」。
 *
 * <p>覆盖：5 片区的介绍与定位、宠物/退房/发票/停车等 FAQ、房源特色、周边徒步/餐饮/亲子攻略。
 * 内容尽量具体、有真实感，保证演示时可解释。生产环境可替换为数据库 / 向量库，接口 {@link #documents()} 不变。
 *
 * <p><b>动态沉淀</b>：{@link #addDocument(String, String, String)} 支持把「联网搜索后整理的新知识」
 * 追加进知识库（同内容去重），下次同类问题可直接命中本地知识库，无需重复联网。
 */
@Component
public class KbStore {

    /** 动态文档自增 ID 前缀。 */
    private final AtomicInteger seq = new AtomicInteger(0);

    /** 内置 15 条初始文档。 */
    private static final List<KbDocument> SEED_DOCS = List.of(
            // ------------------------------------------------------------ 片区介绍
            new KbDocument("area-yucun",
                    "片区介绍",
                    "庾村片区是莫干山的镇中心与老街区，离商业街最近，餐饮咖啡集中，吃饭逛街最方便，性价比高，适合喜欢热闹、想步行可达的游客。"),
            new KbDocument("area-laoling",
                    "片区介绍",
                    "劳岭仙潭片区以竹海景观闻名，设计感强的玻璃屋与私汤民宿聚集于此，安静隐世，适合喜欢拍照、发呆、追求设计感的情侣与朋友。"),
            new KbDocument("area-houwu",
                    "片区介绍",
                    "后坞片区海拔较高，视野开阔，可看云海，山顶民宿为主，安静适合看风景，但最后一段山路较陡，新手自驾需谨慎。"),
            new KbDocument("area-fatou",
                    "片区介绍",
                    "筏头片区沿溪流分布，有儿童游乐区与亲水步道，亲子家庭最合适，带孩子玩水、捉鱼、野餐都很方便。"),
            new KbDocument("area-nanlu",
                    "片区介绍",
                    "南路片区是莫干山新开发的网红片区，自带咖啡厅与出片率高的打卡点，适合年轻人拍照，配套还在逐步完善中。"),

            // ------------------------------------------------------------ 政策FAQ
            new KbDocument("faq-pet",
                    "政策FAQ",
                    "关于宠物：多数民宿允许小型宠物（猫、狗）入住，但需提前告知房东并可能收取清洁费，大型犬或未提前沟通可能被拒绝，请以具体房源政策为准。"),
            new KbDocument("faq-checkout",
                    "政策FAQ",
                    "关于退房：标准退房时间为中午 12:00 前，部分房源可协商延迟退房（视当天房态而定），需提前与房东确认。"),
            new KbDocument("faq-invoice",
                    "政策FAQ",
                    "关于发票：支持开具增值税普通发票，请在入住时或离店后向前台/房东提供抬头与税号信息。"),
            new KbDocument("faq-parking",
                    "政策FAQ",
                    "关于停车：各片区民宿大多提供免费停车位，但后坞山路较陡、车位紧张，建议自驾游客提前确认。"),

            // ------------------------------------------------------------ 房源特色
            new KbDocument("feature-design",
                    "房源特色",
                    "设计风房源：如劳岭仙潭的竹海玻璃屋，落地窗看竹海，含私汤，适合追求设计感与安静体验的客人，价格偏高、旺季紧张。"),
            new KbDocument("feature-family",
                    "房源特色",
                    "亲子友好房源：如筏头溪谷亲子屋，有儿童游乐区、近溪水，适合带孩子玩水，旺季亲子客较多略喧闹。"),
            new KbDocument("feature-yuanmu",
                    "房源特色",
                    "原木风房源：如庾村白墙小院与仙潭原木别院，原木质感、温馨自然，适合喜欢日式简约、追求安静的客人。"),

            // ------------------------------------------------------------ 游玩攻略
            new KbDocument("guide-hiking",
                    "游玩攻略",
                    "徒步攻略：莫干山有多条徒步线路，劳岭仙潭与后坞周边线路较多，适合晨间徒步，建议穿防滑鞋、带足饮水。"),
            new KbDocument("guide-food",
                    "游玩攻略",
                    "餐饮攻略：庾村一带餐饮最集中，本地农家菜与咖啡厅多，其他片区以民宿餐为主，建议提前确认用餐安排。"),
            new KbDocument("guide-family-play",
                    "游玩攻略",
                    "亲子游玩攻略：筏头溪流适合玩水捉鱼、可野餐，配合亲子民宿入住体验更佳，注意看护好孩子、远离深水区。")
    );

    /** 动态知识库（初始含 15 条种子文档，之后可持续追加联网沉淀的知识）。 */
    private final List<KbDocument> docs = new CopyOnWriteArrayList<>(SEED_DOCS);

    public List<KbDocument> documents() {
        return docs;
    }

    /**
     * 追加一条知识文档（联网结果沉淀）。
     *
     * @param id       文档 ID（为空则自动生成）
     * @param category 分类（如「联网沉淀」）
     * @param content  文档内容
     * @return 实际存入的文档 ID；若内容已存在（去重）则返回 {@code null}
     */
    public synchronized String addDocument(String id, String category, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        // 去重：同内容（忽略空白差异）不重复追加
        for (KbDocument d : docs) {
            if (d.content().trim().equals(trimmed)) {
                return null;
            }
        }
        String docId = (id == null || id.isBlank())
                ? "web-" + seq.incrementAndGet()
                : id;
        docs.add(new KbDocument(docId, category == null ? "联网沉淀" : category, trimmed));
        return docId;
    }
}
