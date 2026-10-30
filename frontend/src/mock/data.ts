/**
 * 门户页面 Mock 数据层。
 *
 * 说明：按规划书「先用 mock 数据看到完整网站效果」，后续接真实后端时，
 * 只需把本文件里的常量替换为 API 调用，页面组件无需改动（字段保持对齐即可）。
 */

/** 民宿实体（对齐规划书 homestay 表字段）。 */
export interface Homestay {
  homestayId: string
  title: string
  price: number
  address: string
  tags: string[] // 规范化标签：山景/亲子/loft/海景/温泉等，供 AI 筛选与看图匹配
  image: string
  description: string
  /** 所属片区（用于首页分组 / 地域知识） */
  area: string
  /** 评分（1-5 星，两位小数） */
  score: number
  /** 床型说明（如 1张1.8米大床） */
  bedType?: string
  /** 最大入住人数 */
  maxGuests?: number
  /** 图片画廊（封面 + 补充图） */
  gallery?: string[]
}

/** 景点实体（对齐规划书 spot 表字段）。 */
export interface Spot {
  spotId: string
  name: string
  location: string
  openingHours: string
  image: string
  description: string
}

/** 订单实体（对齐规划书 order 表字段，中文状态枚举）。 */
export interface Order {
  orderId: string
  homestayId: string
  homestayTitle: string
  homestayImage: string
  checkInDate: string
  checkOutDate: string
  nights: number
  totalPrice: number
  contactPhone: string
  status: '待支付' | '已支付' | '已完成' | '已取消'
}

/** 轮播横幅。 */
export interface Banner {
  title: string
  desc: string
  bg: string
  /** 真实照片背景图（优先于 bg 渐变） */
  image?: string
}

/** 民宿封面图（使用 Unsplash 占位图，后续可换真实房源图）。 */
const img = (seed: string): string =>
  `https://images.unsplash.com/${seed}?w=800&q=80&auto=format&fit=crop`

/** 推荐民宿列表（覆盖全国多地）。 */
export const homestayList: Homestay[] = [
  {
    homestayId: 'hs-001',
    title: '莫干山·竹海云栖山景房',
    price: 880,
    address: '浙江省湖州市德清县莫干山·北麓竹林',
    tags: ['山景', '竹海', 'loft'],
    image: img('photo-1600585154340-be6161a56a0c'),
    description:
      '独栋 loft 山景房，落地窗正对整片竹海，清晨推窗即是云雾缭绕的山谷。配备地暖、原木家具与独立茶室，适合情侣静享二人时光。入住当天 18:00 前可免费取消。',
    area: '莫干山',
    score: 4.8,
    bedType: '1张1.8米大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-002',
    title: '莫干山·原木亲子树屋',
    price: 1080,
    address: '浙江省湖州市德清县莫干山镇·仙潭村',
    tags: ['亲子', '原木', '山景'],
    image: img('photo-1600566753190-17f0baa2a6c3'),
    description:
      '童话感树屋，儿童房带滑梯与星空天窗，公共区有绘本角和手作工坊。院子开阔，适合带娃家庭亲近自然。',
    area: '莫干山',
    score: 4.7,
    bedType: '1张1.8米大床 + 1张1.2米儿童床',
    maxGuests: 4,
  },
  {
    homestayId: 'hs-003',
    title: '莫干山·禅意温泉别院',
    price: 1380,
    address: '浙江省湖州市德清县莫干山·庾村老街旁',
    tags: ['温泉', '禅意', '山景'],
    image: img('photo-1600607687939-ce8a6c25118c'),
    description:
      '独享私汤温泉小院，日式禅意庭院与枯山水造景，房间带独立泡池。管家一对一服务，含双人早晚餐。',
    area: '莫干山',
    score: 4.9,
    bedType: '1张2米特大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-004',
    title: '莫干山·设计感网红民宿',
    price: 760,
    address: '浙江省湖州市德清县莫干山·劳岭村',
    tags: ['网红', '设计', '竹海'],
    image: img('photo-1600573472592-401b489a3cdc'),
    description:
      '获奖设计团队打造的极简白房子，几何线条与光影层次感极强，出片率满分。屋顶泳池可观日落，是年轻人打卡首选。',
    area: '莫干山',
    score: 4.6,
    bedType: '1张1.8米大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-005',
    title: '莫干山·溪畔听风木屋',
    price: 920,
    address: '浙江省湖州市德清县莫干山·溪口村',
    tags: ['溪流', '原木', '亲子'],
    image: img('photo-1600585154526-990dced4db0d'),
    description:
      '临溪而建的原木小木屋，白天可溯溪戏水，夜晚伴溪流入眠。带独立露台与烧烤区，适合朋友聚会或亲子出游。',
    area: '莫干山',
    score: 4.7,
    bedType: '2张1.5米双人床',
    maxGuests: 4,
  },
  {
    homestayId: 'hs-006',
    title: '莫干山·云顶玻璃星空房',
    price: 1580,
    address: '浙江省湖州市德清县莫干山·顶峰民宿集群',
    tags: ['山景', '网红', '温泉'],
    image: img('photo-1600607688969-a5bfcd646154'),
    description:
      '270 度玻璃星空房，躺在床上看云海日出与满天繁星。山顶绝佳观景位，含私汤与观景露台，适合纪念日与求婚。',
    area: '莫干山',
    score: 5.0,
    bedType: '1张2米特大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-007',
    title: '大理·洱海月畔海景房',
    price: 720,
    address: '云南省大理市喜洲镇洱海边',
    tags: ['海景', '日落', '情侣', '露台'],
    image: img('photo-1582719478250-c89cae4dc85b'),
    description:
      '洱海东岸一线海景房，露台直面苍山洱海，日落时分景色绝美。含双人下午茶，支持在线支付。',
    area: '云南大理',
    score: 4.9,
    bedType: '1张2米特大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-008',
    title: '丽江·古城纳西别院',
    price: 520,
    address: '云南省丽江市古城区五一街',
    tags: ['古城', '纳西院落', '免费WiFi'],
    image: img('photo-1564501049412-61c2a3083791'),
    description:
      '古城里的纳西族传统院落，木结构老宅改造，安静闲适，步行即可逛遍大研古城。',
    area: '云南丽江',
    score: 4.7,
    bedType: '2张1.5米双人床',
    maxGuests: 4,
  },
  {
    homestayId: 'hs-009',
    title: '北京·胡同四合院',
    price: 880,
    address: '北京市东城区南锣鼓巷',
    tags: ['四合院', '胡同', '文化', '含早餐'],
    image: img('photo-1600596542815-ffad4c1539a9'),
    description:
      '南锣鼓巷深处的老北京四合院，青砖灰瓦，感受地道胡同生活，紧邻地铁出行便利。',
    area: '北京',
    score: 4.8,
    bedType: '1张1.8米大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-010',
    title: '成都·宽窄巷子民宿',
    price: 460,
    address: '四川省成都市青羊区宽窄巷子',
    tags: ['市中心', '美食', '地铁', '亲子'],
    image: img('photo-1600585154340-be6161a56a0c'),
    description:
      '宽窄巷子旁，下楼即是成都美食天堂，串串、火锅、盖碗茶一应俱全，交通便利。',
    area: '四川成都',
    score: 4.6,
    bedType: '2张1.5米双人床',
    maxGuests: 4,
  },
  {
    homestayId: 'hs-011',
    title: '三亚·海棠湾海景房',
    price: 1380,
    address: '海南省三亚市海棠区海棠湾',
    tags: ['海景', '泳池', '度假', '亲子'],
    image: img('photo-1571896349842-33c89424de2d'),
    description:
      '一线海景度假房，无边泳池，沙滩近在咫尺，适合情侣蜜月与亲子度假。',
    area: '海南三亚',
    score: 4.9,
    bedType: '1张2米特大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-012',
    title: '厦门·鼓浪屿老别墅',
    price: 620,
    address: '福建省厦门市思明区鼓浪屿',
    tags: ['海岛', '历史建筑', '文艺', '花园'],
    image: img('photo-1551882547-ff40c63fe5fa'),
    description:
      '鼓浪屿上的百年老别墅改造，红砖洋楼带独立花园，文艺气息扑面而来。',
    area: '福建厦门',
    score: 4.8,
    bedType: '1张1.8米大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-013',
    title: '桂林·阳朔山水客栈',
    price: 480,
    address: '广西壮族自治区桂林市阳朔县西街',
    tags: ['山水', '田园', '骑行', '露台'],
    image: img('photo-1500530855697-b586d89ba3ee'),
    description:
      '阳朔遇龙河畔，推窗即见喀斯特山水，可租自行车骑行田园，惬意慢生活。',
    area: '广西桂林',
    score: 4.7,
    bedType: '2张1.5米双人床',
    maxGuests: 4,
  },
  {
    homestayId: 'hs-014',
    title: '杭州·西湖畔茶舍',
    price: 680,
    address: '浙江省杭州市西湖区龙井村',
    tags: ['茶园', '西湖', '幽静', '含早餐'],
    image: img('photo-1584132967334-10e028bd69f7'),
    description:
      '龙井茶园间的茶舍民宿，清晨茶香四溢，步行可达西湖，含地道杭州早餐。',
    area: '浙江杭州',
    score: 4.8,
    bedType: '1张1.8米大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-015',
    title: '西安·城墙边民宿',
    price: 420,
    address: '陕西省西安市碑林区顺城巷',
    tags: ['城墙', '历史', '美食', '市中心'],
    image: img('photo-1590490360182-c33d57733427'),
    description:
      '紧邻西安城墙，顺城巷夜生活丰富，步行逛回民街，感受千年古都烟火气。',
    area: '陕西西安',
    score: 4.5,
    bedType: '1张1.5米大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-016',
    title: '苏州·平江路枕水人家',
    price: 540,
    address: '江苏省苏州市姑苏区平江路',
    tags: ['水乡', '园林', '江南', '文艺'],
    image: img('photo-1542314831-068cd1dbfeeb'),
    description:
      '平江路临水而居，白墙黛瓦，小桥流水，步行可达拙政园、狮子林，江南韵味十足。',
    area: '江苏苏州',
    score: 4.7,
    bedType: '1张1.8米大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-017',
    title: '重庆·洪崖洞江景民宿',
    price: 500,
    address: '重庆市渝中区洪崖洞',
    tags: ['江景', '夜景', '火锅', '网红'],
    image: img('photo-1557804506-669a67965ba0'),
    description:
      '洪崖洞旁高层江景房，俯瞰嘉陵江夜景，下楼即是火锅与小吃街，山城魅力尽收眼底。',
    area: '重庆',
    score: 4.6,
    bedType: '1张1.8米大床',
    maxGuests: 2,
  },
  {
    homestayId: 'hs-018',
    title: '青岛·八大关海景房',
    price: 650,
    address: '山东省青岛市市南区八大关',
    tags: ['海景', '欧式建筑', '栈桥', '亲子'],
    image: img('photo-1540541338287-41700207dee6'),
    description:
      '八大关欧式建筑群旁，红瓦绿树碧海蓝天，步行到海边栈桥，适合全家出游。',
    area: '山东青岛',
    score: 4.7,
    bedType: '2张1.5米双人床',
    maxGuests: 4,
  },
]

/** 景点列表。 */
export const spotList: Spot[] = [
  {
    spotId: 'sp-001',
    name: '莫干山风景区',
    location: '莫干山镇',
    openingHours: '08:00 - 17:00',
    image: img('photo-1506905925346-21bda4d32df4'),
    description:
      '国家 4A 级景区，以竹、泉、云"三胜"和清、静、绿、凉"四优"驰名中外，与北戴河、庐山、鸡公山并称中国四大避暑胜地。景区内两百多栋民国名人别墅散落竹海之间，剑池飞瀑传为干将莫邪铸剑之地，芦花荡公园四季有景。建议清晨登山看云海日出，午后走剑池—芦花荡环线，全程约三小时；夏季平均气温仅 24℃，是最旺的季节，冬季则可观赏江南罕见的雾凇。门票含景区内接驳车，自驾车辆需停放在山下换乘中心。',
  },
  {
    spotId: 'sp-002',
    name: '庾村文化市集',
    location: '莫干山镇庾村',
    openingHours: '09:00 - 21:00',
    image: img('photo-1518005020951-eccb494ad742'),
    description:
      '由民国时期的蚕种场老厂房改造而成的文艺街区，保留了红砖墙、水塔与老式铁轨，如今聚集了独立咖啡馆、手作工坊、文创小店与山野餐厅。每逢周末有农夫市集，能买到德清本地的笋干、莫干黄芽茶和手作竹器。推荐傍晚时分来逛，老厂房在暖黄灯光下格外出片；市集里的自行车主题餐厅由旧车站改造，是必打卡的地标。整条街区不收门票，停车便利，适合安排半日慢游。',
  },
  {
    spotId: 'sp-003',
    name: '莫干山国家登山健身步道',
    location: '莫干山镇',
    openingHours: '全天开放',
    image: img('photo-1551632811-561732d1e306'),
    description:
      '全长约 100 公里的国家级登山健身步道系统，分初、中、高多条线路，沿途竹海、溪流、瀑布与古村落交错。最经典的"蒋公道"段从庾村出发直达山顶，约 8 公里、爬升 600 米，普通体力三小时可完成；亲子家庭可选劳岭水库环线，平缓好走且春有野花秋有红叶。步道全程免费，设有清晰路标和休憩亭，每年秋季的莫干山越野跑赛事即在此举行。雨后石阶湿滑，建议穿防滑徒步鞋并带足饮水。',
  },
  {
    spotId: 'sp-004',
    name: '仙潭村萤火虫水森林',
    location: '莫干山镇仙潭村',
    openingHours: '18:00 - 22:00（夏季限定）',
    image: img('photo-1501854140801-50d01698950b'),
    description:
      '夏季限定的萤火虫观赏秘境，每年六月中旬到八月初，入夜后成千上万只萤火虫沿溪谷林间飞舞，宛如走入童话森林。村子为保护环境实行限流预约制，每晚仅开放三百个名额，建议提前在村口服务中心登记。观赏时请关闭手电与手机闪光灯，红光模式照明不影响萤火虫活动；溪边步道约一公里，走完全程四十分钟。这里是亲子自然教育和情侣约会的浪漫之选，周边的仙潭美术馆与稻田咖啡馆也值得顺道一逛。',
  },
  {
    spotId: 'sp-005',
    name: '武陵村名人别墅群',
    location: '莫干山镇武陵村',
    openingHours: '08:30 - 16:30',
    image: '/spots/sp-005.jpg',
    description:
      '莫干山人文精华所在，两百余栋建于清末民初的别墅依山就势散落在竹海间，汇集了英、法、美、俄等十余国建筑风格，被誉为"世界近代建筑博物馆"。蒋介石与宋美龄度蜜月的松月庐、毛泽东下榻过的皇后饭店均可入内参观，斑驳的石墙与老式百叶窗保存完好。建议请一位讲解员（约 100 元/场），那些藏在门牌后的民国往事远比建筑本身精彩。村内石阶较多，穿舒适的平底鞋为宜，慢慢逛完约需两小时。',
  },
  {
    spotId: 'sp-006',
    name: '下渚湖国家湿地公园',
    location: '德清县下渚湖街道',
    openingHours: '08:30 - 16:30',
    image: '/spots/sp-006.jpg',
    description:
      '江南最大的天然湿地之一，港汊纵横、芦苇成片，号称"中国最美湿地"。乘船穿行是标准玩法：游船依次停靠竹楼岛、朱鹮岛等几个小岛，能看到国宝朱鹮在水杉林间踱步，秋季芦花飞雪时景色最盛。湿地还是防风古国的传说发源地，岛上有防风祠与茶文化体验园，可品一杯当地的三道茶。全程游览约两个半小时，从莫干山镇驱车二十分钟即达，适合与莫干山行程拼成两日游。',
  },
  {
    spotId: 'sp-007',
    name: '洱海生态廊道',
    location: '云南大理·洱海西岸',
    openingHours: '全天开放',
    image: '/spots/sp-007.jpg',
    description:
      '环绕洱海西岸修建的 46 公里生态廊道，机动车禁行，只有自行车、电瓶车与行人，是亲近洱海最体面的方式。从才村码头出发向北，沿途经过网红 S 湾、廊桥、海舌公园，每个转角都是"苍山不墨千秋画"的实景。推荐租一辆自行车（约 20 元/天）骑行精华段，清晨看洱海日出、傍晚追苍山日落；廊道边的白族村落里藏着不少咖啡馆，走累了随时可以停下来对着海发呆。注意高原紫外线强烈，防晒务必做足。',
  },
  {
    spotId: 'sp-008',
    name: '丽江古城',
    location: '云南丽江·古城区',
    openingHours: '全天开放',
    image: '/spots/sp-008.jpg',
    description:
      '始建于宋末元初的世界文化遗产，八百年历史的纳西族古都。四方街是古城的中心，清晨人少时石板路上还能听见流水声；沿玉河溯流而上可至大水车与许愿铃长廊，登上狮子山万古楼则能俯瞰整座古城的灰色瓦海与远处的玉龙雪山。夜晚的酒吧街热闹非凡，但更推荐拐进五一街的巷子，找一家纳西庭院听场民谣。古城维护费已取消，进出自由；高原昼夜温差大，即使夏天也建议带件外套。',
  },
  {
    spotId: 'sp-009',
    name: '成都大熊猫繁育研究基地',
    location: '四川成都·成华区',
    openingHours: '07:30 - 18:00',
    image: '/spots/sp-009.jpg',
    description:
      '全球最著名的大熊猫保护机构，常年生活着两百多只大熊猫，从刚出生的粉红幼崽到憨态可掬的亚成体都能看到。游览的核心诀窍只有一个字：早。熊猫上午九点前最活跃，吃竹、爬树、打闹花样百出，十点半以后基本集体躺平睡觉。从南门进园直奔月亮产房与幼年园，再沿竹林步道往下走，全程约三小时。基地距市区约十公里，地铁三号线可达；旺季务必提前在官方公众号预约购票，现场不售票。',
  },
  {
    spotId: 'sp-010',
    name: '西湖风景名胜区',
    location: '浙江杭州·西湖区',
    openingHours: '全天开放（部分收费景点 07:30 - 17:30）',
    image: '/spots/sp-010.jpg',
    description:
      '"未能抛得杭州去，一半勾留是此湖"。西湖十景四季各有名场面：春看苏堤春晓的桃柳相间，夏赏曲院风荷的接天莲叶，秋夜平湖秋月，冬候断桥残雪。环湖约 15 公里，体力好可步行或骑共享单车走"断桥—白堤—苏堤—雷峰塔"大环线，想省力则坐环湖电瓶车或画舫游船。湖畔的中国美院、浙江美术馆常年有展，逛累了去湖边茶座喝一杯明前龙井才是正经事。核心湖区免费开放，雷峰塔、岳庙等小景点单独售票。',
  },
  {
    spotId: 'sp-011',
    name: '亚龙湾热带天堂森林公园',
    location: '海南三亚·亚龙湾',
    openingHours: '07:30 - 17:30',
    image: '/spots/sp-011.jpg',
    description:
      '电影《非诚勿扰 2》的取景地，三亚离城市最近的热带雨林。景区依山而建，观光车盘旋上山，山顶的玻璃栈桥与沧海楼可 270 度俯瞰亚龙湾的月牙形海岸线，海水分层出深浅不同的蓝。过江龙索桥横跨两山之间，是电影里的名场面，走上去微微晃动，惊险但不危险。雨林里可玩滑索穿越，全程约三分钟飞跃树冠层。建议安排半天，上午登山观景，下午下山去亚龙湾沙滩踏浪，一日山海两相宜。',
  },
  {
    spotId: 'sp-012',
    name: '鼓浪屿',
    location: '福建厦门·思明区',
    openingHours: '全天开放（轮渡 07:10 - 17:30）',
    image: '/spots/sp-012.jpg',
    description:
      '1.88 平方公里的小岛，却藏着上千栋风格各异的老别墅与"万国建筑博览"的美誉。岛上没有机动车，最好的游览方式就是迷路：从三丘田码头随意拐进任何一条巷子，红砖古厝、三角梅与琴声会一路相伴。日光岩是全岛制高点，登顶可同时望见厦门本岛与金门；菽庄花园把大海"藏"进了庭院，钢琴博物馆里收藏着上百架古董钢琴。游客需从邮轮中心厦鼓码头乘船上岛，船票 35 元往返，节假日务必提前线上购票。',
  },
]

/** 我的订单（mock）。 */
export const orderList: Order[] = [
  {
    orderId: 'OR202610290001001',
    homestayId: 'hs-001',
    homestayTitle: '莫干山·竹海云栖山景房',
    homestayImage: img('photo-1600585154340-be6161a56a0c'),
    checkInDate: '2026-11-01',
    checkOutDate: '2026-11-03',
    nights: 2,
    totalPrice: 1760,
    contactPhone: '13800001111',
    status: '已支付',
  },
  {
    orderId: 'OR202610280002001',
    homestayId: 'hs-003',
    homestayTitle: '莫干山·禅意温泉别院',
    homestayImage: img('photo-1600607687939-ce8a6c25118c'),
    checkInDate: '2026-11-15',
    checkOutDate: '2026-11-16',
    nights: 1,
    totalPrice: 1380,
    contactPhone: '13800001111',
    status: '待支付',
  },
  {
    orderId: 'OR202610200003001',
    homestayId: 'hs-006',
    homestayTitle: '莫干山·云顶玻璃星空房',
    homestayImage: img('photo-1600607688969-a5bfcd646154'),
    checkInDate: '2026-10-24',
    checkOutDate: '2026-10-26',
    nights: 2,
    totalPrice: 3160,
    contactPhone: '13800001111',
    status: '已完成',
  },
]

/** 首页轮播横幅。 */
export const banners: Banner[] = [
  {
    title: '发现美好民宿',
    desc: '精选全国各地特色民宿，等你来住',
    bg: 'linear-gradient(135deg, #667eea, #764ba2)',
    image: img('photo-1600585154340-be6161a56a0c'),
  },
  {
    title: '周边景点推荐',
    desc: '住得好，也要玩得尽兴',
    bg: 'linear-gradient(135deg, #f093fb, #f5576c)',
    image: img('photo-1500530855697-b586d89ba3ee'),
  },
  {
    title: 'AI 对话预约',
    desc: '一句话需求，带走一个决断',
    bg: 'linear-gradient(135deg, #4facfe, #00f2fe)',
    image: img('photo-1571896349842-33c89424de2d'),
  },
]

/** 按 id 查民宿。 */
export function findHomestay(id: string): Homestay | undefined {
  return homestayList.find((h) => h.homestayId === id)
}

/** 按 id 查景点。 */
export function findSpot(id: string): Spot | undefined {
  return spotList.find((s) => s.spotId === id)
}
