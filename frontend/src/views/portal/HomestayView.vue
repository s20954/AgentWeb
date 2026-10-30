<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { homestayList, type Homestay } from '@/mock/data'

const router = useRouter()
const keyword = ref('')
const activeTag = ref<string>('')
const activeArea = ref<string>('')

// 全部可选标签（去重）
const allTags = computed(() => {
  const set = new Set<string>()
  homestayList.forEach((h) => h.tags.forEach((t) => set.add(t)))
  return Array.from(set)
})

// 全部可选地区（去重）
const allAreas = computed(() => {
  const set = new Set<string>()
  homestayList.forEach((h) => set.add(h.area))
  return Array.from(set)
})

// 筛选后的列表
const filtered = computed(() => {
  return homestayList.filter((h) => {
    const kw = keyword.value.trim()
    const matchKw = !kw || h.title.includes(kw) || h.address.includes(kw) || h.area.includes(kw)
    const matchTag = !activeTag.value || h.tags.includes(activeTag.value)
    const matchArea = !activeArea.value || h.area === activeArea.value
    return matchKw && matchTag && matchArea
  })
})

function goDetail(h: Homestay): void {
  router.push(`/homestay/${h.homestayId}`)
}
</script>

<template>
  <div>
    <!-- 搜索栏 -->
    <div class="flex flex-col sm:flex-row gap-3 mb-5">
      <div class="flex-1 relative">
        <input
          v-model="keyword"
          type="text"
          placeholder="搜索民宿名称 / 地址 / 片区"
          class="w-full h-11 pl-10 pr-4 rounded-xl border border-stone-200 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition"
        />
        <svg viewBox="0 0 24 24" fill="none" class="w-5 h-5 absolute left-3 top-1/2 -translate-y-1/2 text-stone-400" stroke="currentColor" stroke-width="1.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
        </svg>
      </div>
    </div>

    <!-- 地区筛选 -->
    <div class="flex flex-wrap gap-2 mb-3">
      <button
        type="button"
        class="px-3 py-1.5 rounded-full text-sm transition-colors"
        :class="!activeArea ? 'bg-emerald-700 text-white' : 'bg-white text-stone-600 border border-stone-200 hover:border-emerald-300'"
        @click="activeArea = ''"
      >
        全部地区
      </button>
      <button
        v-for="a in allAreas"
        :key="a"
        type="button"
        class="px-3 py-1.5 rounded-full text-sm transition-colors"
        :class="activeArea === a ? 'bg-emerald-700 text-white' : 'bg-white text-stone-600 border border-stone-200 hover:border-emerald-300'"
        @click="activeArea = a"
      >
        {{ a }}
      </button>
    </div>

    <!-- 标签筛选 -->
    <div class="flex flex-wrap gap-2 mb-6">
      <button
        type="button"
        class="px-3 py-1.5 rounded-full text-sm transition-colors"
        :class="!activeTag ? 'bg-emerald-700 text-white' : 'bg-white text-stone-600 border border-stone-200 hover:border-emerald-300'"
        @click="activeTag = ''"
      >
        全部
      </button>
      <button
        v-for="t in allTags"
        :key="t"
        type="button"
        class="px-3 py-1.5 rounded-full text-sm transition-colors"
        :class="activeTag === t ? 'bg-emerald-700 text-white' : 'bg-white text-stone-600 border border-stone-200 hover:border-emerald-300'"
        @click="activeTag = t"
      >
        {{ t }}
      </button>
    </div>

    <!-- 结果计数 -->
    <p class="mb-4 text-sm text-stone-500">共 {{ filtered.length }} 家民宿</p>

    <!-- 民宿卡片列表 -->
    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
      <div
        v-for="item in filtered"
        :key="item.homestayId"
        class="bg-white rounded-xl overflow-hidden cursor-pointer hover:-translate-y-1 hover:shadow-lg transition-all group"
        @click="goDetail(item)"
      >
        <div class="h-48 overflow-hidden">
          <img :src="item.image" :alt="item.title" class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
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
          <div class="mt-2 text-emerald-700 text-lg font-bold">¥{{ item.price }} <span class="text-xs font-normal text-stone-400">/ 晚</span></div>
          <div class="mt-2 flex flex-wrap gap-1.5">
            <span v-for="(t, i) in item.tags" :key="i" class="px-2 py-0.5 rounded-md bg-stone-100 text-stone-600 text-xs">{{ t }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 空态 -->
    <div v-if="!filtered.length" class="py-20 text-center text-stone-400">
      <p class="text-lg mb-2">没有找到匹配的民宿</p>
      <p class="text-sm">换个关键词或标签试试</p>
    </div>
  </div>
</template>
