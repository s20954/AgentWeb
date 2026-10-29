<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { findSpot, type Spot } from '@/mock/data'

const route = useRoute()
const router = useRouter()
const spot = computed<Spot | undefined>(() => findSpot(String(route.params.id)))
</script>

<template>
  <div v-if="spot">
    <button type="button" class="mb-4 inline-flex items-center gap-1 text-sm text-stone-500 hover:text-emerald-700 transition-colors" @click="router.back()">
      <svg viewBox="0 0 24 24" fill="none" class="w-4 h-4" stroke="currentColor" stroke-width="1.5">
        <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 19.5L8.25 12l7.5-7.5" />
      </svg>
      返回
    </button>

    <div class="bg-white rounded-2xl overflow-hidden shadow-sm">
      <img :src="spot.image" :alt="spot.name" class="w-full h-80 object-cover" />
      <div class="p-6">
        <h1 class="text-2xl font-bold text-stone-800">{{ spot.name }}</h1>
        <div class="mt-3 flex flex-col sm:flex-row gap-3 text-sm text-stone-600">
          <span class="flex items-center gap-1.5">
            <svg viewBox="0 0 24 24" fill="none" class="w-4 h-4 text-stone-400" stroke="currentColor" stroke-width="1.5">
              <path stroke-linecap="round" stroke-linejoin="round" d="M15 10.5a3 3 0 11-6 0 3 3 0 016 0z" />
              <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 10.5c0 7.142-7.5 11.25-7.5 11.25S4.5 17.642 4.5 10.5a7.5 7.5 0 1115 0z" />
            </svg>
            {{ spot.location }}
          </span>
          <span class="flex items-center gap-1.5">
            <svg viewBox="0 0 24 24" fill="none" class="w-4 h-4 text-stone-400" stroke="currentColor" stroke-width="1.5">
              <path stroke-linecap="round" stroke-linejoin="round" d="M12 6v6h4.5m4.5 0a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            {{ spot.openingHours }}
          </span>
        </div>
        <div class="mt-6">
          <h3 class="text-lg font-semibold text-stone-800 mb-2">景点介绍</h3>
          <p class="text-stone-600 leading-relaxed">{{ spot.description }}</p>
        </div>
      </div>
    </div>
  </div>

  <div v-else class="py-24 text-center text-stone-400">
    <p class="text-lg mb-2">未找到该景点</p>
    <button type="button" class="text-emerald-700 hover:underline" @click="router.push('/spot')">返回景点列表</button>
  </div>
</template>
