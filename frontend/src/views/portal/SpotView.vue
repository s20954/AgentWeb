<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { spotList, type Spot } from '@/mock/data'

const router = useRouter()
const activeArea = ref<string>('')

// 全部可选地区（去重，保持出现顺序）
const allAreas = computed(() => {
  const set = new Set<string>()
  spotList.forEach((s) => set.add(s.area))
  return Array.from(set)
})

// 按地区分组后的景点：[{ area, spots }]
const grouped = computed(() => {
  const map = new Map<string, Spot[]>()
  for (const s of spotList) {
    if (activeArea.value && s.area !== activeArea.value) continue
    if (!map.has(s.area)) map.set(s.area, [])
    map.get(s.area)!.push(s)
  }
  return Array.from(map, ([area, spots]) => ({ area, spots }))
})

function goDetail(s: Spot): void {
  router.push(`/spot/${s.spotId}`)
}
</script>

<template>
  <div>
    <h2 class="text-xl font-bold text-stone-800 mb-5">周边景点</h2>

    <!-- 地区筛选 -->
    <div class="flex flex-wrap gap-2 mb-6">
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

    <!-- 按地区分组展示 -->
    <section v-for="g in grouped" :key="g.area" class="mb-10">
      <div class="flex items-center gap-3 mb-4">
        <span class="w-1.5 h-5 rounded-full bg-emerald-600"></span>
        <h3 class="text-lg font-semibold text-stone-800">{{ g.area }}</h3>
        <span class="text-sm text-stone-400">{{ g.spots.length }} 个景点</span>
      </div>

      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
        <div
          v-for="s in g.spots"
          :key="s.spotId"
          class="bg-white rounded-xl overflow-hidden cursor-pointer hover:-translate-y-1 hover:shadow-lg transition-all group"
          @click="goDetail(s)"
        >
          <div class="h-44 overflow-hidden">
            <img :src="s.image" :alt="s.name" class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
          </div>
          <div class="p-4">
            <div class="font-semibold text-stone-800">{{ s.name }}</div>
            <div class="mt-1 text-sm text-stone-500 flex items-center gap-1">
              <svg viewBox="0 0 24 24" fill="none" class="w-4 h-4 shrink-0" stroke="currentColor" stroke-width="1.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M15 10.5a3 3 0 11-6 0 3 3 0 016 0z" />
                <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 10.5c0 7.142-7.5 11.25-7.5 11.25S4.5 17.642 4.5 10.5a7.5 7.5 0 1115 0z" />
              </svg>
              {{ s.location }}
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 空态 -->
    <div v-if="!grouped.length" class="py-20 text-center text-stone-400">
      <p class="text-lg mb-2">该地区暂无景点</p>
      <p class="text-sm">换个地区看看</p>
    </div>
  </div>
</template>
