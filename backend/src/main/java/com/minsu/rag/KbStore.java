package com.minsu.rag;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RAG 知识库存储 —— 内置 15 条莫干山中文知识文档，并支持「联网结果沉淀回知识库」。
 *
 * <p>覆盖：5 片区的介绍与定位、宠物/退房/发票/停车等 FAQ、房源特色、周边徒步/餐饮/亲子攻略。
 * 内容尽量具体、有真实感，保证演示时可解释。生产环境可替换为数据库 / 向量库，接口 {@link #documents()} 不变。
 *
 * <p><b>动态沉淀 + SQLite 持久化</b>：{@link #addDocument(String, String, String)} 支持把「联网搜索后整理的新知识」
 * 追加进知识库（同内容去重），下次同类问题可直接命中本地知识库，无需重复联网。
 * 沉淀的知识会同步写入 SQLite（{@link KbRepository}），服务重启后自动加载回来，实现「知识复利、重启不丢」。
 */
@Component
public class KbStore {

    private static final Logger log = LoggerFactory.getLogger(KbStore.class);

    /** 动态文档自增 ID 前缀。 */
    private final AtomicInteger seq = new AtomicInteger(0);

    /** SQLite 持久化仓库（仅用于沉淀知识的落盘与加载）。 */
    private final KbRepository repository;

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
                    "亲子游玩攻略：筏头溪流适合玩水捉鱼、可野餐，配合亲子民宿入住体验更佳，注意看护好孩子、远离深水区。"),

            // ------------------------------------------------------------ 成都 片区介绍
            new KbDocument("cd-area-chunxi",
                    "片区介绍",
                    "春熙路片区是成都的市中心与商业核心，太古里、IFS 就在楼下，逛街吃饭最方便，近地铁出行便利，适合喜欢繁华、想逛商圈、出行以公共交通为主的游客。"),
            new KbDocument("cd-area-kuanzhai",
                    "片区介绍",
                    "宽窄巷子片区保留川西民居院落风貌，青瓦灰砖、茶馆戏楼，人文气息浓，步行即可逛宽窄巷子、泡茶馆，适合喜欢传统文化、想体验老成都慢生活的游客。"),
            new KbDocument("cd-area-jinli",
                    "片区介绍",
                    "锦里片区紧邻武侯祠，是成都美食与三国文化的集中地，出门就是美食街，小吃众多，适合带家人、喜欢热闹与美食的游客，节假日人流较大。"),

            // ------------------------------------------------------------ 成都 房源特色 / 攻略
            new KbDocument("cd-feature-design",
                    "房源特色",
                    "成都设计风房源：如春熙路太古里隐庐，下楼即太古里，闹中取静，适合追求设计感与便利的年轻人；宽窄巷子青瓦别院则主打川西民居体验，原木质感、人文气息浓。"),
            new KbDocument("cd-guide-food",
                    "游玩攻略",
                    "成都美食攻略：火锅、串串、兔头、钟水饺等小吃集中在春熙路、锦里、宽窄巷子一带，锦里美食街与春熙路商圈选择最多，宽窄巷子适合体验盖碗茶与川剧。"),

            // ------------------------------------------------------------ 重庆 片区介绍
            new KbDocument("cq-area-jiefangbei",
                    "片区介绍",
                    "解放碑片区是重庆市中心与地标，洪崖洞、长江索道都在附近，高楼层江景房可俯瞰两江交汇，夜景绝佳，适合第一次来重庆、想看夜景与繁华的游客。"),
            new KbDocument("cq-area-nanbin",
                    "片区介绍",
                    "南滨路片区沿江分布，安静且江景开阔，正对渝中半岛，是看对岸夜景的绝佳位置，适合喜欢安静、想住江景房的游客，离地铁站略远。"),
            new KbDocument("cq-area-ciqikou",
                    "片区介绍",
                    "磁器口片区是千年古镇与码头文化代表，青石板路、吊脚楼、麻花陈酿，逛古镇吃小吃方便，适合喜欢人文、带家人的游客，古镇游客较多。"),

            // ------------------------------------------------------------ 重庆 房源特色 / 攻略
            new KbDocument("cq-feature-river",
                    "房源特色",
                    "重庆江景房：如解放碑云端江景房、南滨路江畔别院，高楼层或临江而建，夜晚看两江交汇与渝中夜景非常出片，适合情侣与追求体验的游客。"),
            new KbDocument("cq-guide-night",
                    "游玩攻略",
                    "重庆夜景攻略：洪崖洞、解放碑、南滨路是三大夜景打卡点，长江索道跨江可看全貌，山城坡多台阶多，建议穿舒适平底鞋，行李多时优先选近地铁的房源。"),

            // ------------------------------------------------------------ 上海 片区介绍
            new KbDocument("sh-area-waitan",
                    "片区介绍",
                    "外滩片区是上海地标与万国建筑群所在地，老洋房与现代都会公寓并存，步行即达外滩、南京路，适合第一次来上海、想感受海派风情与繁华的游客。"),
            new KbDocument("sh-area-wukang",
                    "片区介绍",
                    "武康路片区是上海梧桐区的文艺代表，洋房、咖啡馆、买手店林立，漫步梧桐道最有上海腔调，适合喜欢文艺、拍照、慢逛的游客。"),
            new KbDocument("sh-area-xintiandi",
                    "片区介绍",
                    "新天地片区以石库门建筑与时尚地标闻名，餐饮、酒吧、夜生活丰富，适合喜欢时尚与夜生活的年轻游客，周末人流较大。"),

            // ------------------------------------------------------------ 上海 房源特色 / 攻略
            new KbDocument("sh-feature-lao",
                    "房源特色",
                    "上海老洋房：如外滩老洋房、武康路梧桐小院，海派建筑、木地板与老式钢窗，年代感与设计感兼具，适合追求格调与体验的客人，老房子隔音相对一般。"),
            new KbDocument("sh-guide-citywalk",
                    "游玩攻略",
                    "上海 Citywalk 攻略：外滩—南京路—武康路—新天地是一天经典线路，梧桐区咖啡馆多适合小坐，地铁发达建议住近地铁的房源，省时省力。"),

            // ------------------------------------------------------------ 杭州 片区介绍
            new KbDocument("hz-area-xihu",
                    "片区介绍",
                    "西湖片区是杭州核心景区，湖景房推窗见湖，步行即达西湖与湖滨商圈，江南韵味浓，适合第一次来杭州、想住湖边赏景的游客，旺季价格偏高。"),
            new KbDocument("hz-area-lingyin",
                    "片区介绍",
                    "灵隐片区位于龙井茶园与灵隐寺之间，茶园环绕、禅意清静，适合想静养、礼佛、亲近自然的游客，离市区较远，适合自驾或打车出行。"),
            new KbDocument("hz-area-hefang",
                    "片区介绍",
                    "河坊街片区是南宋御街与老杭州风貌的集中地，青砖黛瓦、小吃众多，逛老街、品杭帮菜方便，适合喜欢人文与美食的游客，游客较多。"),

            // ------------------------------------------------------------ 杭州 房源特色 / 攻略
            new KbDocument("hz-feature-yuanmu",
                    "房源特色",
                    "杭州原木风房源：如灵隐茶园别院、河坊宋韵小筑，原木质感、江南韵味，茶园或老街环绕，适合追求安静、禅意与在地体验的客人。"),
            new KbDocument("hz-guide-tea",
                    "游玩攻略",
                    "杭州游玩攻略：西湖环湖、灵隐寺祈福、龙井茶园采茶是经典玩法，河坊街适合品尝杭帮菜与小吃，环湖骑行或打车都方便，旺季建议提前订湖景房。")
    );

    /** 动态知识库（初始含 15 条种子文档，之后可持续追加联网沉淀的知识）。 */
    private final List<KbDocument> docs = new CopyOnWriteArrayList<>(SEED_DOCS);

    public KbStore(KbRepository repository) {
        this.repository = repository;
        // 启动时从 SQLite 加载历史沉淀的知识，实现「重启不丢」
        int loaded = 0;
        int maxSeq = 0;
        for (KbDocument d : repository.loadAll()) {
            if (d.content() != null && !d.content().isBlank()) {
                docs.add(d);
                loaded++;
                // 把已有 "web-N" 沉淀的最大序号作为 seq 水位：否则重启后 seq 归 0，
                // 新生成的 id 与库中已有主键冲突，被 INSERT OR IGNORE 静默丢弃，知识重启后丢失
                if (d.id() != null && d.id().startsWith("web-")) {
                    try {
                        maxSeq = Math.max(maxSeq, Integer.parseInt(d.id().substring(4)));
                    } catch (NumberFormatException ignored) {
                        // 非数字后缀的 id 不影响水位
                    }
                }
            }
        }
        seq.set(maxSeq);
        if (loaded > 0) {
            log.info("已从 SQLite 加载 {} 条沉淀知识（seq 水位={}）", loaded, maxSeq);
        }
    }

    public List<KbDocument> documents() {
        return docs;
    }

    /**
     * 追加一条知识文档（联网结果沉淀），并同步写入 SQLite 持久化。
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
        // 去重：同内容（忽略空白差异）不重复追加（内存 + SQLite 双查）
        for (KbDocument d : docs) {
            if (d.content().trim().equals(trimmed)) {
                return null;
            }
        }
        String docId = (id == null || id.isBlank())
                ? "web-" + seq.incrementAndGet()
                : id;
        String cat = category == null ? "联网沉淀" : category;

        // 先写 SQLite（成功与否都继续进内存，保证本次检索可用）
        repository.insert(docId, cat, trimmed);

        docs.add(new KbDocument(docId, cat, trimmed));
        return docId;
    }
}
