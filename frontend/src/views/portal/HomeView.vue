<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { homestayList, banners, type Homestay } from '@/mock/data'

const router = useRouter()
const currentBanner = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

function next(): void {
  currentBanner.value = (currentBanner.value + 1) % banners.length
}
function go(id: number): void {
  currentBanner.value = id
}

onMounted(() => {
  timer = setInterval(next, 4000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

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
    <!-- 轮播横幅（真实照片背景） -->
    <div class="relative h-64 sm:h-80 rounded-2xl overflow-hidden shadow-sm">
      <div
        v-for="(b, i) in banners"
        :key="i"
        class="absolute inset-0 flex flex-col items-center justify-center text-white transition-opacity duration-700"
        :style="{
          backgroundImage: b.image ? `url(${b.image})` : undefined,
          backgroundSize: 'cover',
          backgroundPosition: 'center',
          background: !b.image ? b.bg : undefined,
          opacity: currentBanner === i ? 1 : 0,
        }"
      >
        <div class="absolute inset-0 bg-gradient-to-r from-black/50 to-black/10"></div>
        <div class="relative z-10 flex flex-col items-center">
          <h2 class="text-3xl sm:text-4xl font-bold mb-2 drop-shadow-lg">{{ b.title }}</h2>
          <p class="text-sm sm:text-base opacity-95 drop-shadow">{{ b.desc }}</p>
        </div>
      </div>
      <!-- 指示点 -->
      <div class="absolute bottom-4 left-1/2 -translate-x-1/2 flex gap-2 z-10">
        <button
          v-for="(_b, i) in banners"
          :key="i"
          type="button"
          class="w-2 h-2 rounded-full transition-colors"
          :class="currentBanner === i ? 'bg-white' : 'bg-white/50'"
          @click="go(i)"
        />
      </div>
    </div>

    <!-- AI 助手入口横幅 -->
    <div class="mt-6 rounded-2xl bg-gradient-to-r from-emerald-700 to-teal-600 p-6 sm:p-8 text-white flex flex-col sm:flex-row sm:items-center justify-between gap-4">
      <div>
        <h3 class="text-xl sm:text-2xl font-bold mb-1">不知道住哪？让 AI 帮你决定</h3>
        <p class="text-sm sm:text-base text-emerald-50">一句话描述需求，Agent 七步收敛，直接给你一个能决策的推荐。</p>
      </div>
      <button
        type="button"
        class="shrink-0 px-6 py-3 rounded-xl bg-white text-emerald-700 font-semibold hover:bg-emerald-50 transition-colors"
        @click="router.push('/agent')"
      >
        立即体验
      </button>
    </div>

    <!-- 推荐民宿（按地区分组） -->
    <template v-for="g in groups" :key="g.area">
      <div class="mt-8 mb-4 flex items-baseline justify-between">
        <h3 class="text-xl font-bold text-stone-800">{{ g.area }}</h3>
        <span class="text-sm text-stone-400">共 {{ g.items.length }} 家民宿</span>
      </div>
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
        <div
          v-for="item in g.items"
          :key="item.homestayId"
          class="bg-white rounded-xl overflow-hidden cursor-pointer hover:-translate-y-1 hover:shadow-lg transition-all group"
          @click="goDetail(item)"
        >
          <div class="relative h-44 overflow-hidden">
            <img :src="item.image" :alt="item.title" class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
            <span class="absolute left-2.5 top-2.5 px-2 py-0.5 rounded-full bg-black/50 text-white text-xs">{{ item.area }}</span>
          </div>
          <div class="p-4">
            <div class="flex items-start justify-between gap-2">
              <div class="font-semibold text-stone-800 truncate">{{ item.title }}</div>
              <div class="shrink-0 flex items-center gap-1 text-amber-500 text-sm">
                <svg viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4"><path d="M11.48 3.5c.31-.64 1.27-.64 1.58 0l1.79 3.63 4 .58c.71.1.99.97.48 1.47l-2.89 2.82.68 3.98c.12.7-.62 1.24-1.25.9L12 14.62l-3.57 1.88c-.63.33-1.37-.2-1.25-.9l.68-3.98-2.89-2.82c-.51-.5-.23-1.37.48-1.47l4-.58 1.79-3.63z" /></svg>
                {{ item.score }}
              </div>
            </div>
            <div class="mt-1 text-sm text-stone-500 truncate">{{ item.address }}</div>
            <div class="mt-2 flex items-baseline justify-between">
              <div class="text-emerald-700 text-lg font-bold">¥{{ fmtPrice(item.price) }} <span class="text-xs font-normal text-stone-400">/ 晚</span></div>
              <span v-if="item.maxGuests" class="text-xs text-stone-500">可住 {{ item.maxGuests }} 人</span>
            </div>
            <div class="mt-2 flex flex-wrap gap-1.5">
              <span v-for="(t, i) in item.tags.slice(0, 3)" :key="i" class="px-2 py-0.5 rounded-md bg-stone-100 text-stone-600 text-xs">{{ t }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
