<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { orderList, type Order } from '@/mock/data'

const router = useRouter()
const activeStatus = ref<string>('')

const statuses = ['待支付', '已支付', '已完成', '已取消'] as const

const filtered = computed(() => {
  if (!activeStatus.value) return orderList
  return orderList.filter((o) => o.status === activeStatus.value)
})

// 状态配色
function statusClass(s: Order['status']): string {
  switch (s) {
    case '待支付':
      return 'bg-amber-50 text-amber-600'
    case '已支付':
      return 'bg-blue-50 text-blue-600'
    case '已完成':
      return 'bg-emerald-50 text-emerald-600'
    case '已取消':
      return 'bg-stone-100 text-stone-500'
  }
}

// mock 操作：支付/取消
function pay(order: Order): void {
  order.status = '已支付'
}
function cancel(order: Order): void {
  order.status = '已取消'
}
function review(order: Order): void {
  router.push(`/homestay/${order.homestayId}`)
}
</script>

<template>
  <div>
    <h2 class="text-xl font-bold text-stone-800 mb-5">我的订单</h2>

    <!-- 状态筛选 -->
    <div class="flex flex-wrap gap-2 mb-6">
      <button
        type="button"
        class="px-3 py-1.5 rounded-full text-sm transition-colors"
        :class="!activeStatus ? 'bg-emerald-700 text-white' : 'bg-white text-stone-600 border border-stone-200'"
        @click="activeStatus = ''"
      >
        全部
      </button>
      <button
        v-for="s in statuses"
        :key="s"
        type="button"
        class="px-3 py-1.5 rounded-full text-sm transition-colors"
        :class="activeStatus === s ? 'bg-emerald-700 text-white' : 'bg-white text-stone-600 border border-stone-200'"
        @click="activeStatus = s"
      >
        {{ s }}
      </button>
    </div>

    <!-- 订单列表 -->
    <div class="space-y-4">
      <div v-for="order in filtered" :key="order.orderId" class="bg-white rounded-xl p-4 shadow-sm">
        <div class="flex items-center justify-between pb-3 border-b border-stone-100">
          <span class="text-xs text-stone-400">订单号：{{ order.orderId }}</span>
          <span class="px-2.5 py-1 rounded-md text-xs font-medium" :class="statusClass(order.status)">{{ order.status }}</span>
        </div>
        <div class="flex gap-4 pt-3">
          <div class="w-24 h-24 rounded-lg overflow-hidden shrink-0 cursor-pointer" @click="router.push(`/homestay/${order.homestayId}`)">
            <img :src="order.homestayImage" :alt="order.homestayTitle" class="w-full h-full object-cover" />
          </div>
          <div class="flex-1 min-w-0">
            <div class="font-medium text-stone-800 truncate">{{ order.homestayTitle }}</div>
            <div class="mt-1 text-sm text-stone-500">
              {{ order.checkInDate }} 至 {{ order.checkOutDate }} · {{ order.nights }} 晚
            </div>
            <div class="mt-1 text-sm text-stone-500">联系电话：{{ order.contactPhone }}</div>
            <div class="mt-2 flex flex-wrap items-center justify-between gap-3">
              <span class="text-emerald-700 font-bold">¥{{ order.totalPrice }}</span>
              <div class="flex gap-2">
                <button v-if="order.status === '待支付'" type="button" class="px-3 py-1.5 rounded-lg bg-emerald-700 text-white text-sm hover:bg-emerald-800" @click="pay(order)">支付</button>
                <button v-if="order.status === '待支付'" type="button" class="px-3 py-1.5 rounded-lg border border-stone-200 text-stone-600 text-sm hover:border-stone-300" @click="cancel(order)">取消</button>
                <button v-if="order.status === '已完成'" type="button" class="px-3 py-1.5 rounded-lg border border-amber-200 text-amber-600 text-sm hover:bg-amber-50" @click="review(order)">评价</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 空态 -->
      <div v-if="!filtered.length" class="py-20 text-center text-stone-400">
        <p class="text-lg mb-2">暂无相关订单</p>
        <button type="button" class="text-emerald-700 hover:underline" @click="router.push('/homestay')">去逛逛民宿</button>
      </div>
    </div>
  </div>
</template>
