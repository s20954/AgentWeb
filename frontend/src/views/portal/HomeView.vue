<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
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

// 价格转字符串（保留两位小数）
function fmtPrice(n: number): string {
  return n.toFixed(0)
}
</script>

<template>
  <div>
    <!-- 轮播横幅 -->
    <div class="relative h-64 sm:h-72 rounded-2xl overflow-hidden shadow-sm">
      <div
        v-for="(b, i) in banners"
        :key="i"
        class="absolute inset-0 flex flex-col items-center justify-center text-white transition-opacity duration-500"
        :style="{ background: b.bg, opacity: currentBanner === i ? 1 : 0 }"
      >
        <h2 class="text-3xl sm:text-4xl font-bold mb-2">{{ b.title }}</h2>
        <p class="text-sm sm:text-base opacity-90">{{ b.desc }}</p>
      </div>
      <!-- 指示点 -->
      <div class="absolute bottom-4 left-1/2 -translate-x-1/2 flex gap-2">
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

    <!-- 推荐民宿 -->
    <h3 class="mt-8 mb-4 text-xl font-bold text-stone-800">推荐民宿</h3>
    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
      <div
        v-for="item in homestayList"
        :key="item.homestayId"
        class="bg-white rounded-xl overflow-hidden cursor-pointer hover:-translate-y-1 hover:shadow-lg transition-all group"
        @click="goDetail(item)"
      >
        <div class="h-44 overflow-hidden">
          <img :src="item.image" :alt="item.title" class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
        </div>
        <div class="p-4">
          <div class="font-semibold text-stone-800 truncate">{{ item.title }}</div>
          <div class="mt-1 text-emerald-700 text-lg font-bold">¥{{ fmtPrice(item.price) }} <span class="text-xs font-normal text-stone-400">/ 晚</span></div>
          <div class="mt-2 flex flex-wrap gap-1.5">
            <span v-for="(t, i) in item.tags" :key="i" class="px-2 py-0.5 rounded-md bg-stone-100 text-stone-600 text-xs">{{ t }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
