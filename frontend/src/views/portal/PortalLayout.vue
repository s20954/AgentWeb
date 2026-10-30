<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

// 移动端抽屉菜单开关
const menuOpen = ref(false)
const isMobile = ref(false)

function checkMobile(): void {
  isMobile.value = window.innerWidth < 768
  if (!isMobile.value) menuOpen.value = false
}

onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
})
onUnmounted(() => window.removeEventListener('resize', checkMobile))

// 导航项（高亮通过 router-link-active 自动处理）
const navItems = [
  { to: '/home', label: '首页' },
  { to: '/homestay', label: '民宿' },
  { to: '/spot', label: '景点' },
  { to: '/agent', label: 'AI 助手' },
  { to: '/order', label: '我的订单' },
]

function go(path: string): void {
  menuOpen.value = false
  router.push(path)
}
</script>

<template>
  <div class="min-h-screen flex flex-col bg-[#f7f7f5]">
    <!-- 顶部导航 -->
    <header class="sticky top-0 z-50 bg-white/90 backdrop-blur border-b border-stone-200">
      <div class="max-w-6xl mx-auto h-16 px-4 sm:px-6 flex items-center justify-between">
        <!-- Logo -->
        <button type="button" class="flex items-center gap-2" @click="go('/home')">
          <span class="w-8 h-8 rounded-lg bg-emerald-700 flex items-center justify-center text-white font-semibold text-sm">栖</span>
          <span class="text-lg font-bold text-stone-800 tracking-wide">栖迟</span>
        </button>

        <!-- 桌面导航 -->
        <nav class="hidden md:flex items-center gap-8">
          <router-link
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="text-[15px] text-stone-600 hover:text-emerald-700 transition-colors"
            active-class="!text-emerald-700 font-semibold"
          >
            {{ item.label }}
          </router-link>
        </nav>

        <!-- 右侧：个人中心 + 移动端汉堡 -->
        <div class="flex items-center gap-3">
          <button
            type="button"
            class="hidden sm:inline-flex items-center gap-1.5 text-sm text-stone-600 hover:text-emerald-700 transition-colors"
            @click="go('/profile')"
          >
            <svg viewBox="0 0 24 24" fill="none" class="w-5 h-5" stroke="currentColor" stroke-width="1.5">
              <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.5 20.25a8.25 8.25 0 0115 0" />
            </svg>
            个人中心
          </button>
          <!-- 移动端汉堡按钮 -->
          <button type="button" class="md:hidden p-1 text-stone-600" @click="menuOpen = !menuOpen">
            <svg viewBox="0 0 24 24" fill="none" class="w-6 h-6" stroke="currentColor" stroke-width="1.5">
              <path v-if="!menuOpen" stroke-linecap="round" stroke-linejoin="round" d="M3.75 6.75h16.5M3.75 12h16.5M3.75 17.25h16.5" />
              <path v-else stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      </div>

      <!-- 移动端下拉菜单 -->
      <transition name="fade">
        <nav v-if="menuOpen" class="md:hidden border-t border-stone-100 bg-white">
          <button
            v-for="item in navItems"
            :key="item.to"
            type="button"
            class="block w-full text-left px-6 py-3 text-[15px] text-stone-700 hover:bg-stone-50"
            @click="go(item.to)"
          >
            {{ item.label }}
          </button>
          <button type="button" class="block w-full text-left px-6 py-3 text-[15px] text-stone-700 hover:bg-stone-50" @click="go('/profile')">
            个人中心
          </button>
        </nav>
      </transition>
    </header>

    <!-- 内容区 -->
    <main class="flex-1 w-full max-w-6xl mx-auto px-4 sm:px-6 py-6">
      <router-view />
    </main>

    <!-- 页脚 -->
    <footer class="border-t border-stone-200 bg-white">
      <div class="max-w-6xl mx-auto px-4 sm:px-6 py-8 text-center text-sm text-stone-500">
        <p class="mb-1">栖迟 Agent · 一语既出，佳宿自现</p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
