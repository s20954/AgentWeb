<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

// mock 用户信息
const user = ref({
  name: '张三',
  avatar: '',
  phone: '138****1111',
  balance: 2000,
  joinDate: '2026-10-01',
})

// 模拟充值
const rechargeAmount = ref('')
const rechargeSuccess = ref(false)

function recharge(): void {
  const n = Number(rechargeAmount.value)
  if (!n || n <= 0) return
  user.value.balance += n
  rechargeSuccess.value = true
  rechargeAmount.value = ''
  setTimeout(() => (rechargeSuccess.value = false), 2000)
}

function goAgent(): void {
  router.push('/agent')
}
</script>

<template>
  <div class="max-w-2xl mx-auto">
    <!-- 用户卡片 -->
    <div class="bg-white rounded-2xl p-6 shadow-sm">
      <div class="flex items-center gap-4">
        <div class="w-16 h-16 rounded-full bg-emerald-100 flex items-center justify-center text-emerald-700 text-2xl font-bold">
          {{ user.name.slice(0, 1) }}
        </div>
        <div>
          <div class="text-lg font-semibold text-stone-800">{{ user.name }}</div>
          <div class="text-sm text-stone-500">{{ user.phone }} · 加入于 {{ user.joinDate }}</div>
        </div>
      </div>

      <!-- 余额卡片 -->
      <div class="mt-6 rounded-xl bg-gradient-to-r from-emerald-700 to-teal-600 p-5 text-white">
        <div class="text-sm text-emerald-50">账户余额</div>
        <div class="mt-1 text-3xl font-bold">¥{{ user.balance.toFixed(2) }}</div>
      </div>

      <!-- 充值 -->
      <div class="mt-5">
        <h3 class="font-semibold text-stone-800 mb-3">余额充值</h3>
        <div class="flex gap-2">
          <input
            v-model="rechargeAmount"
            type="number"
            min="1"
            placeholder="输入充值金额"
            class="flex-1 h-11 px-3 rounded-xl border border-stone-200 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500"
          />
          <button type="button" class="px-5 h-11 rounded-xl bg-emerald-700 text-white font-medium hover:bg-emerald-800 transition-colors" @click="recharge">充值</button>
        </div>
        <p v-if="rechargeSuccess" class="mt-2 text-sm text-emerald-600">充值成功！</p>
      </div>
    </div>

    <!-- 快捷入口 -->
    <div class="mt-5 bg-white rounded-2xl p-4 shadow-sm">
      <button type="button" class="flex items-center justify-between w-full py-3 px-2 hover:bg-stone-50 rounded-lg transition-colors" @click="router.push('/order')">
        <span class="text-stone-700">我的订单</span>
        <svg viewBox="0 0 24 24" fill="none" class="w-5 h-5 text-stone-400" stroke="currentColor" stroke-width="1.5"><path stroke-linecap="round" stroke-linejoin="round" d="M8.25 4.5l7.5 7.5-7.5 7.5" /></svg>
      </button>
      <button type="button" class="flex items-center justify-between w-full py-3 px-2 hover:bg-stone-50 rounded-lg transition-colors" @click="goAgent">
        <span class="text-stone-700">AI 预约助手</span>
        <svg viewBox="0 0 24 24" fill="none" class="w-5 h-5 text-stone-400" stroke="currentColor" stroke-width="1.5"><path stroke-linecap="round" stroke-linejoin="round" d="M8.25 4.5l7.5 7.5-7.5 7.5" /></svg>
      </button>
    </div>
  </div>
</template>
