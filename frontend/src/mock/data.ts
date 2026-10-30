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
    image: img('photo-1557928723-c2f3b0e5e25b'),
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
    image: img('photo-1552975284-3cb21ac92792'),
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
      '国家 4A 级景区，以竹、泉、云"三胜"和清、静、绿、凉"四优"驰名。夏季避暑、冬季观雾凇，剑池飞瀑与芦花荡公园是不可错过的景点。',
  },
  {
    spotId: 'sp-002',
    name: '庾村文化市集',
    location: '莫干山镇庾村',
    openingHours: '09:00 - 21:00',
    image: img('photo-1518005020951-eccb494ad742'),
    description:
      '文艺范十足的民国风情街区，聚集了咖啡店、手作工坊、文创店与特色餐厅，是年轻人逛吃打卡的热门地。',
  },
  {
    spotId: 'sp-003',
    name: '莫干山国家登山健身步道',
    location: '莫干山镇',
    openingHours: '全天开放',
    image: img('photo-1551632811-561732d1e306'),
    description:
      '全长约 100 公里的森林步道，沿途竹海、溪流、古村落交错，适合徒步、骑行与越野跑，是亲近自然的好去处。',
  },
  {
    spotId: 'sp-004',
    name: '仙潭村萤火虫水森林',
    location: '莫干山镇仙潭村',
    openingHours: '18:00 - 22:00（夏季限定）',
    image: img('photo-1501854140801-50d01698950b'),
    description:
      '夏季限定的萤火虫观赏秘境，夜晚成片萤火虫在林间飞舞，宛如走入童话森林，是亲子游与情侣约会的浪漫之地。',
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
