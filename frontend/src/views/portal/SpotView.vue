<script setup lang="ts">
import { useRouter } from 'vue-router'
import { spotList, type Spot } from '@/mock/data'

const router = useRouter()

function goDetail(s: Spot): void {
  router.push(`/spot/${s.spotId}`)
}
</script>

<template>
  <div>
    <h2 class="text-xl font-bold text-stone-800 mb-5">周边景点</h2>
    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
      <div
        v-for="s in spotList"
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
            <svg viewBox="0 0 24 24" fill="none" class="w-4 h-4" stroke="currentColor" stroke-width="1.5">
              <path stroke-linecap="round" stroke-linejoin="round" d="M15 10.5a3 3 0 11-6 0 3 3 0 016 0z" />
              <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 10.5c0 7.142-7.5 11.25-7.5 11.25S4.5 17.642 4.5 10.5a7.5 7.5 0 1115 0z" />
            </svg>
            {{ s.location }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
