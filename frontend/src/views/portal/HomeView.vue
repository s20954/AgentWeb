<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { homestayList, type Homestay } from '@/mock/data'

const router = useRouter()

/** 首屏搜索词：带去对话页预填，用户确认后发送。 */
const heroQuery = ref('')

function goAgent(q?: string): void {
  const query = (q ?? heroQuery.value).trim()
  if (query) {
    router.push({ path: '/agent', query: { q: query } })
  } else {
    router.push('/agent')
  }
}

/** 快捷意象词条：一键带词进入对话。 */
const heroChips = ['莫干山 · 竹海私汤', '成都 · 亲子院落', '大理 · 临海白桌', '带宠物入住']

/** 页面底部信息区：快速访问导航。 */
const quickLinks = [
  { to: '/home', label: '首页' },
  { to: '/homestay', label: '民宿' },
  { to: '/spot', label: '景点' },
  { to: '/agent', label: 'AI 助手' },
  { to: '/order', label: '我的订单' },
]

function goDetail(h: Homestay): void {
  router.push(`/homestay/${h.homestayId}`)
}

// 价格转字符串（保留整数）
function fmtPrice(n: number): string {
  return n.toFixed(0)
}

// 按地区分组（area 即地区）
const groups = computed(() => {
  const map = new Map<string, Homestay[]>()
  homestayList.forEach((h) => {
    const key = h.area || '其他'
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(h)
  })
  return Array.from(map.entries()).map(([area, items]) => ({ area, items }))
})
</script>

<template>
  <div>
    <!-- 沉浸式 hero：墨绿深色 + 宋体大字 + 搜索即入口 -->
    <section class="hero relative overflow-hidden rounded-2xl shadow-lg">
      <div class="hero-bg absolute inset-0"></div>
      <div class="absolute inset-0 bg-gradient-to-b from-[#0A1A17]/60 via-[#0F2420]/70 to-[#0A1A17]/90"></div>
      <div class="relative z-10 flex flex-col items-center text-center px-6 py-16 sm:py-24">
        <p class="text-xs sm:text-sm tracking-[0.5em] text-[#C8A96A] mb-4">衡门之下 · 可以栖迟</p>
        <h1 class="font-serif-cn text-5xl sm:text-6xl font-semibold text-[#F3EFE7] tracking-[0.2em]">栖·迟</h1>
        <p class="mt-4 text-sm sm:text-base text-[#F3EFE7]/70 tracking-[0.3em]">一语既出 · 佳宿自现</p>

        <!-- 搜索即入口 -->
        <div class="mt-9 w-full max-w-xl">
          <div class="flex items-center rounded-full bg-[#F7F5F0]/95 shadow-[0_10px_36px_rgba(0,0,0,0.4)] pl-6 pr-2 py-2">
            <input
              v-model="heroQuery"
              type="text"
              placeholder="说说你想去的地方、同行的人…"
              class="flex-1 bg-transparent outline-none text-sm sm:text-base text-stone-700 placeholder-stone-400"
              @keyup.enter="goAgent()"
            />
            <button
              type="button"
              class="shrink-0 px-5 sm:px-7 py-2.5 rounded-full bg-[#1E4A3F] text-[#F7F5F0] text-sm font-medium hover:bg-[#163a31] transition-colors"
              @click="goAgent()"
            >
              为我定宿
            </button>
          </div>
          <div class="mt-4 flex flex-wrap justify-center gap-2">
            <button
              v-for="c in heroChips"
              :key="c"
              type="button"
              class="px-3.5 py-1.5 rounded-full border border-[#F3EFE7]/25 text-xs text-[#F3EFE7]/65 hover:text-[#F3EFE7] hover:border-[#C8A96A]/60 transition-colors"
              @click="goAgent(c)"
            >
              {{ c }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- 推荐民宿（按地区分组） -->
    <template v-for="g in groups" :key="g.area">
      <div class="mt-12 mb-5 flex items-baseline gap-3">
        <span class="w-1 h-5 rounded-full bg-[#C8A96A]"></span>
        <h3 class="font-serif-cn text-2xl font-semibold text-stone-800 tracking-wider">{{ g.area }}</h3>
        <span class="text-sm text-stone-400">共 {{ g.items.length }} 家民宿</span>
      </div>
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
        <div
          v-for="item in g.items"
          :key="item.homestayId"
          class="bg-white rounded-xl overflow-hidden cursor-pointer shadow-sm hover:shadow-xl hover:-translate-y-1 transition-all duration-300 group"
          @click="goDetail(item)"
        >
          <div class="relative aspect-[4/3] overflow-hidden">
            <img :src="item.image" :alt="item.title" class="w-full h-full object-cover group-hover:scale-110 transition-transform duration-500" />
            <span class="absolute left-3 top-3 px-2.5 py-1 rounded-full bg-black/45 backdrop-blur-sm text-white text-xs">{{ item.area }}</span>
            <span class="absolute right-3 bottom-3 px-2.5 py-1 rounded-full bg-[#C8A96A]/95 text-[#2A2118] text-xs font-semibold">
              ¥{{ fmtPrice(item.price) }} / 晚
            </span>
          </div>
          <div class="p-4">
            <div class="flex items-start justify-between gap-2">
              <div class="font-semibold text-stone-800 truncate">{{ item.title }}</div>
              <div class="shrink-0 flex items-center gap-1 text-[#B8913F] text-sm">
                <svg viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4"><path d="M11.48 3.5c.31-.64 1.27-.64 1.58 0l1.79 3.63 4 .58c.71.1.99.97.48 1.47l-2.89 2.82.68 3.98c.12.7-.62 1.24-1.25.9L12 14.62l-3.57 1.88c-.63.33-1.37-.2-1.25-.9l.68-3.98-2.89-2.82c-.51-.5-.23-1.37.48-1.47l4-.58 1.79-3.63z" /></svg>
                {{ item.score }}
              </div>
            </div>
            <div class="mt-1 text-sm text-stone-500 truncate">{{ item.address }}</div>
            <div class="mt-2.5 flex flex-wrap gap-1.5">
              <span v-for="(t, i) in item.tags.slice(0, 3)" :key="i" class="px-2 py-0.5 rounded-md bg-stone-100 text-stone-600 text-xs">{{ t }}</span>
              <span v-if="item.maxGuests" class="px-2 py-0.5 rounded-md bg-stone-100 text-stone-600 text-xs">可住 {{ item.maxGuests }} 人</span>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 关于项目：浅色通栏信息区（铺满浏览器 100% 宽度） -->
    <section class="mt-14 bg-[#EDEAE3] text-stone-700 overflow-hidden w-screen relative left-1/2 -translate-x-1/2">
      <div class="max-w-6xl mx-auto px-4 sm:px-6">
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-8 py-12 sm:py-14">
          <!-- 关于项目 -->
          <div>
            <h4 class="font-serif-cn text-lg font-semibold text-emerald-800 tracking-wider mb-3">关于项目</h4>
            <p class="text-sm leading-6 text-stone-600">
              栖迟 Agent 是一款 AI 驱动的民宿预订平台，支持联网搜索、需求缺口追问、看图选房与语音输入。一句话需求，即可为你收敛出一个可决策的住宿方案。
            </p>
          </div>

          <!-- 技术支持 -->
          <div>
            <h4 class="font-serif-cn text-lg font-semibold text-emerald-800 tracking-wider mb-3">技术支持</h4>
            <ul class="text-sm leading-7 text-stone-600">
              <li>前端 · Vue 3 / TypeScript / Tailwind</li>
              <li>后端 · Spring Boot / MyBatis-Plus</li>
              <li>数据 · MySQL / 本地知识库</li>
              <li>AI · DeepSeek LLM + 视觉模型</li>
            </ul>
          </div>

          <!-- 快速访问 -->
          <div>
            <h4 class="font-serif-cn text-lg font-semibold text-emerald-800 tracking-wider mb-3">快速访问</h4>
            <ul class="text-sm leading-7">
              <li v-for="n in quickLinks" :key="n.to">
                <a class="text-stone-600 hover:text-emerald-800 transition-colors cursor-pointer" @click="router.push(n.to)">{{ n.label }}</a>
              </li>
            </ul>
          </div>

          <!-- 联系方式 -->
          <div>
            <h4 class="font-serif-cn text-lg font-semibold text-emerald-800 tracking-wider mb-3">联系方式</h4>
            <ul class="text-sm leading-7 text-stone-600">
              <li>GitHub · s20954 / AgentWeb</li>
              <li>
                <a class="hover:text-emerald-800 transition-colors cursor-pointer" @click="router.push('/agent')">意见反馈 · 通过 AI 助手留言</a>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* 宋体栈：不引外部字体（国内网络环境），用系统衬线 */
.font-serif-cn {
  font-family: 'Noto Serif SC', 'Songti SC', 'STSong', 'SimSun', serif;
}

/* hero 背景：竹林意象 + 墨绿渐变压暗 */
.hero-bg {
  background-image: url('https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?auto=format&fit=crop&w=1600&q=80');
  background-size: cover;
  background-position: center 40%;
}
</style>
