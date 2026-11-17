<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import type { Order } from '@/mock/data'
import { useUserDataStore } from '@/stores/userData'

const router = useRouter()
const userData = useUserDataStore()

const activeStatus = ref<string>('')
const statuses = ['待支付', '已支付', '已完成', '已取消'] as const

const filtered = computed(() => {
  if (!activeStatus.value) return userData.orders
  return userData.orders.filter((o) => o.status === activeStatus.value)
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

function pay(order: Order): void {
  userData.payOrder(order.orderId)
}

function cancel(order: Order): void {
  userData.cancelOrder(order.orderId)
}

// 删除订单（二次确认）
function remove(order: Order): void {
  if (window.confirm(`确定删除订单「${order.homestayTitle}」吗？删除后不可恢复。`)) {
    userData.deleteOrder(order.orderId)
  }
}

// 评价：跳转到民宿详情页的住客评价区
function review(order: Order): void {
  router.push(`/homestay/${order.homestayId}?review=1`)
}

// ---- 联系电话动态修改 ----
/** 正在编辑电话的订单 id（null 表示无）。 */
const editingPhoneId = ref<string>('')
const phoneDraft = ref('')

function startEditPhone(order: Order): void {
  editingPhoneId.value = order.orderId
  phoneDraft.value = order.contactPhone
}

function savePhone(order: Order): void {
  const p = phoneDraft.value.trim()
  if (p && !/^1\d{10}$/.test(p)) {
    window.alert('请输入 11 位手机号（以 1 开头）')
    return
  }
  userData.updateOrderPhone(order.orderId, p)
  editingPhoneId.value = ''
}

function cancelEditPhone(): void {
  editingPhoneId.value = ''
  phoneDraft.value = ''
}

// 图片加载失败标记（记录到订单，回退占位图）
const imgFailed = ref<Record<string, boolean>>({})
function onImgError(orderId: string) {
  imgFailed.value[orderId] = true
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
          <div class="w-24 h-24 rounded-lg overflow-hidden shrink-0 cursor-pointer bg-stone-100" @click="router.push(`/homestay/${order.homestayId}`)">
            <img
              v-if="order.homestayImage && !imgFailed[order.orderId]"
              :src="order.homestayImage"
              :alt="order.homestayTitle"
              class="w-full h-full object-cover"
              @error="onImgError(order.orderId)"
            />
            <div v-else class="w-full h-full flex items-center justify-center">
              <svg viewBox="0 0 24 24" fill="none" class="w-10 h-10 text-stone-400" stroke="currentColor" stroke-width="1.4">
                <path stroke-linecap="round" stroke-linejoin="round" d="m2.25 12 8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75" />
              </svg>
            </div>
          </div>
          <div class="flex-1 min-w-0">
            <div class="font-medium text-stone-800 truncate">{{ order.homestayTitle }}</div>
            <div v-if="order.roomType" class="mt-0.5 text-xs text-emerald-700">房型：{{ order.roomType }}</div>
            <div class="mt-1 text-sm text-stone-500">
              {{ order.checkInDate }} 至 {{ order.checkOutDate }} · {{ order.nights }} 晚
            </div>
            <!-- 联系电话：支持点击动态修改 -->
            <div class="mt-1 text-sm text-stone-500 flex items-center gap-2">
              <span>联系电话：</span>
              <template v-if="editingPhoneId === order.orderId">
                <input
                  v-model="phoneDraft"
                  type="tel"
                  maxlength="11"
                  placeholder="请输入 11 位手机号"
                  class="flex-1 min-w-0 px-2 py-1 rounded-md border border-emerald-300 text-sm text-stone-800 focus:outline-none focus:ring-2 focus:ring-emerald-400"
                />
                <button type="button" class="text-xs text-emerald-700 hover:underline" @click="savePhone(order)">保存</button>
                <button type="button" class="text-xs text-stone-400 hover:underline" @click="cancelEditPhone">取消</button>
              </template>
              <template v-else>
                <span v-if="order.contactPhone" class="text-stone-700">{{ order.contactPhone }}</span>
                <span v-else class="text-stone-400">未填写</span>
                <button
                  type="button"
                  class="text-xs text-emerald-600 hover:text-emerald-800 underline underline-offset-2"
                  title="点击修改联系电话"
                  @click="startEditPhone(order)"
                >
                  修改
                </button>
              </template>
            </div>
            <div class="mt-2 flex flex-wrap items-center justify-between gap-3">
              <span class="text-emerald-700 font-bold">¥{{ order.totalPrice }}</span>
              <div class="flex flex-wrap gap-2">
                <button v-if="order.status === '待支付'" type="button" class="px-3 py-1.5 rounded-lg bg-emerald-700 text-white text-sm hover:bg-emerald-800" @click="pay(order)">支付</button>
                <button v-if="order.status === '待支付'" type="button" class="px-3 py-1.5 rounded-lg border border-stone-200 text-stone-600 text-sm hover:border-stone-300" @click="cancel(order)">取消</button>
                <button v-if="order.status === '已完成'" type="button" class="px-3 py-1.5 rounded-lg border border-amber-200 text-amber-600 text-sm hover:bg-amber-50" @click="review(order)">评价</button>
                <button
                  v-if="order.status !== '待支付'"
                  type="button"
                  class="px-3 py-1.5 rounded-lg border border-stone-200 text-stone-500 text-sm hover:text-red-600 hover:border-red-200"
                  @click="remove(order)"
                >
                  删除
                </button>
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
