package com.minsu.tool.executor;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 房源数据仓库 —— mock 数据（多城市：莫干山 / 成都 / 重庆 / 上海 / 杭州）。
 *
 * <p>本阶段为工具层 mock 数据，各城市按「片区 × 房源」组织，每套含定位+价格+适配理由+一个真实缺点。
 * 真实数据接入是后续阶段。后续若走 RAG/数据库，此接口保持不变，只换实现。
 *
 * <p><b>数据层驱动</b>：{@link #supportedDestinations()} 从 {@link #ROOMS} 动态派生，
 * 是「支持哪些目的地」的唯一真相源——接入新城市只需在此加房源，Agent 层识别零改动。
 */
@Component
public class RoomStore {

    /** 封面图（本地静态资源路径，前端 public/images 下；消除 Unsplash 外链国内加载隐患）。 */
    private static String img(String seed) {
        // seed 形如 "photo-1600585154340-be6161a56a0c"，取中间数字 id 作为文件名
        String id = seed.replaceFirst("^photo-", "").split("-")[0];
        return "/images/" + id + ".jpg";
    }

    /** 多城市 mock 房源（莫干山 5 片区 7 套 + 成都/重庆/上海/杭州 各 4 套）。 */
    private static final List<Room> ROOMS = List.of(
            // 庾村（镇中心/性价比）
            new Room("yg-001", "莫干山", "庾村", "老街里·白墙小院", "镇中心步行可达，性价比高", 420, "原木", "离商业街近，吃饭方便", "临街，周末早上略吵", img("photo-1518005020951-eccb494ad742")),
            new Room("yg-002", "莫干山", "庾村", "庾村·临溪客栈", "推窗见溪，闹中取静", 480, "溪流", "溪景房带露台", "房间偏小", img("photo-1600585154526-990dced4db0d")),
            // 劳岭仙潭（竹海/设计/安静）
            new Room("ll-001", "莫干山", "劳岭仙潭", "竹海·玻璃屋", "落地窗看竹海，设计感强", 1280, "设计", "竹海景观+私汤", "价格偏高，旺季紧张", img("photo-1600607688969-a5bfcd646154")),
            new Room("ll-002", "莫干山", "劳岭仙潭", "仙潭·原木别院", "安静隐世，适合发呆", 980, "原木", "安静，周边徒步线路多", "离镇远，需自驾", img("photo-1600585154340-be6161a56a0c")),
            // 后坞（高海拔/视野好/路陡）
            new Room("hw-001", "莫干山", "后坞", "后坞·云顶民宿", "高海拔视野开阔", 880, "设计", "山顶视野，看云海", "最后一段路陡，新手慎行", img("photo-1506905925346-21bda4d32df4")),
            // 筏头（溪流/亲子）
            new Room("ft-001", "莫干山", "筏头", "筏头·溪谷亲子屋", "溪流边，适合亲子", 650, "亲子", "有儿童游乐区，近溪水", "旺季亲子客多，略喧闹", img("photo-1600566753190-17f0baa2a6c3")),
            // 南路（新开发/网红）
            new Room("nl-001", "莫干山", "南路", "南路·网红咖啡民宿", "新开发片区，出片率高", 720, "网红", "自带咖啡厅，适合拍照", "新片区配套还在完善", img("photo-1600573472592-401b489a3cdc")),

            // ============================================================ 成都
            // 春熙路（市中心/繁华/出行方便）
            new Room("cd-001", "成都", "春熙路", "太古里·隐庐", "太古里旁，闹中取静", 680, "设计", "下楼即太古里，逛街吃饭方便", "临商圈，周末人流大", img("photo-1600585154340-be6161a56a0c")),
            new Room("cd-002", "成都", "春熙路", "春熙·拾光小院", "老城区改造，有川西院子味", 520, "原木", "近地铁，出行方便，性价比高", "老楼隔音一般", img("photo-1600607688969-a5bfcd646154")),
            // 宽窄巷子（人文/体验）
            new Room("cd-003", "成都", "宽窄巷子", "宽窄·青瓦别院", "宽窄巷子旁，川西民居体验", 760, "原木", "步行到宽窄巷子，人文气息浓", "巷子游客多，略喧闹", img("photo-1600566753190-17f0baa2a6c3")),
            // 锦里/武侯祠（人文/美食）
            new Room("cd-004", "成都", "锦里", "锦里·三国小筑", "武侯祠旁，美食环绕", 450, "亲子", "出门就是美食街，适合带家人", "热门景区，节假日拥挤", img("photo-1518005020951-eccb494ad742")),

            // ============================================================ 重庆
            // 解放碑（市中心/繁华）
            new Room("cq-001", "重庆", "解放碑", "解放碑·云端江景房", "高楼层看江景，市中心", 720, "设计", "俯瞰两江交汇，夜景绝佳", "市中心交通拥堵", img("photo-1506905925346-21bda4d32df4")),
            new Room("cq-002", "重庆", "解放碑", "洪崖洞·吊脚楼民宿", "洪崖洞旁，山城特色", 580, "原木", "步行到洪崖洞，夜景出片", "爬坡上坎，行李多费劲", img("photo-1600585154526-990dced4db0d")),
            // 南滨路（江景/安静）
            new Room("cq-003", "重庆", "南滨路", "南滨·江畔别院", "江景房，看对岸夜景", 880, "设计", "安静江景，看渝中夜景", "离地铁站略远", img("photo-1600607688969-a5bfcd646154")),
            // 磁器口（古镇/人文）
            new Room("cq-004", "重庆", "磁器口", "磁器口·古镇小筑", "千年古镇旁，码头文化", 420, "亲子", "逛古镇吃小吃方便", "古镇游客多", img("photo-1600573472592-401b489a3cdc")),

            // ============================================================ 上海
            // 外滩（地标/繁华）
            new Room("sh-001", "上海", "外滩", "外滩·老洋房", "外滩旁老洋房，海派风情", 1280, "设计", "步行到外滩，看万国建筑", "价格高，房型偏小", img("photo-1600585154340-be6161a56a0c")),
            new Room("sh-002", "上海", "外滩", "南京路·都会公寓", "南京路旁，购物方便", 680, "现代", "出门即商圈，地铁便利", "临街略吵", img("photo-1506905925346-21bda4d32df4")),
            // 武康路/衡山路（梧桐区/文艺）
            new Room("sh-003", "上海", "武康路", "武康·梧桐小院", "梧桐区洋房，文艺街区", 880, "原木", "漫步梧桐区，咖啡馆多", "老房子隔音一般", img("photo-1600607688969-a5bfcd646154")),
            // 新天地（时尚/夜生活）
            new Room("sh-004", "上海", "新天地", "新天地·石库门", "石库门建筑，时尚地标", 1080, "现代", "近新天地，夜生活丰富", "周末人流大", img("photo-1600566753190-17f0baa2a6c3")),

            // ============================================================ 杭州
            // 西湖/湖滨（核心景区）
            new Room("hz-001", "杭州", "西湖", "西湖·湖景房", "推窗见西湖，核心地段", 980, "设计", "步行到西湖，赏湖景", "旅游旺季价格高", img("photo-1506905925346-21bda4d32df4")),
            new Room("hz-002", "杭州", "西湖", "湖滨·烟雨小筑", "西湖边，江南韵味", 720, "原木", "近湖滨商圈，出行方便", "景区周边略喧闹", img("photo-1600585154526-990dced4db0d")),
            // 灵隐/龙井（茶园/清静）
            new Room("hz-003", "杭州", "灵隐", "灵隐·茶园别院", "龙井茶园旁，禅意清静", 820, "原木", "茶园环绕，适合静养", "离市区较远", img("photo-1600607688969-a5bfcd646154")),
            // 河坊街/南宋御街（人文/美食）
            new Room("hz-004", "杭州", "河坊街", "河坊·宋韵小筑", "南宋御街旁，老杭州味", 520, "亲子", "逛老街吃小吃方便", "游客较多", img("photo-1600573472592-401b489a3cdc"))
    );

    public List<Room> search(String destination, String area, LocalDate checkIn, LocalDate checkOut,
                             Integer guests, Integer budgetPerNight, String relationship, String style) {
        return ROOMS.stream()
                .filter(r -> r.destination().equals(destination))
                .filter(r -> area == null || area.isBlank() || r.area().equals(area))
                .filter(r -> budgetPerNight == null || r.basePrice() <= budgetPerNight)
                // 风格是「软性偏好」而非硬过滤：风格匹配的排前面，不匹配的也不排除。
                // 否则用户说「喜欢设计风」会把「现代/原木」等相近风格全切掉，反而不符合顾问定位。
                .sorted(Comparator.comparingInt((Room r) ->
                        style == null || style.isBlank() || r.style().equals(style) ? 0 : 1))
                .toList();
    }

    /** 当前数据层是否覆盖该目的地（用于「目的地暂无房源」的诚实提示，区别于「条件过苛刻无匹配」）。 */
    public boolean supportsDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return false;
        }
        return ROOMS.stream().anyMatch(r -> r.destination().equals(destination));
    }

    /**
     * 当前数据层覆盖的所有目的地（去重）。
     *
     * <p>这是「支持哪些目的地」的唯一真相源：Agent 层不再维护城市白名单，
     * 以后接入新城市数据，只需往 {@link #ROOMS} 加房源，此处自动派生，
     * Agent 层识别与校验零改动。
     */
    public List<String> supportedDestinations() {
        return ROOMS.stream().map(Room::destination).distinct().toList();
    }

    public Room byId(String roomId) {
        return ROOMS.stream().filter(r -> r.roomId().equals(roomId)).findFirst().orElse(null);
    }
}
