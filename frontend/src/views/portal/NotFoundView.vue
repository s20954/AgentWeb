<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="min-h-[60vh] flex flex-col items-center justify-center text-center">
    <div class="text-7xl font-bold text-emerald-700">404</div>
    <p class="mt-4 text-lg text-stone-600">页面不存在或已被移动</p>
    <button
      type="button"
      class="mt-6 px-6 py-3 rounded-xl bg-emerald-700 text-white font-medium hover:bg-emerald-800 transition-colors"
      @click="router.push('/home')"
    >
      返回首页
    </button>
  </div>
</template>
