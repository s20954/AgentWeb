<script setup lang="ts">
import { computed, ref } from 'vue'
import { payOrder } from '@/api/tools'
import { getOrCreateUserId } from '@/api/chat'

const props = defineProps<{
  /** Agent 回复文本（下单后含订单号 MS...） */
  content: string
}>()

/** 从文本中提取订单号（格式 MS<时间戳>-<序号>）。 */
const orderId = computed(() => {
  const m = props.content.match(/MS\d{10,}-\d+/)
  return m ? m[0] : ''
})

/** 是否处于「待支付」状态（含订单号 + 待支付字眼才显示支付按钮）。 */
const isPending = computed(() => orderId.value !== '' && props.content.includes('待支付'))

/** 支付状态：null=未支付，'paying'=支付中，'paid'=已支付，'error'=失败 */
const payState = ref<'paying' | 'paid' | 'error' | null>(null)
const payError = ref('')

async function handlePay() {
  if (!orderId.value || payState.value === 'paying') return
  payState.value = 'paying'
  payError.value = ''
  try {
    const resp = await payOrder(orderId.value, getOrCreateUserId())
    if (resp.success && resp.data && 'paid' in resp.data && resp.data.paid) {
      payState.value = 'paid'
    } else {
      const msg = (resp.data && 'message' in resp.data && resp.data.message) || resp.note || '支付失败，请稍后重试'
      payError.value = msg
      payState.value = 'error'
    }
  } catch (e) {
    payError.value = e instanceof Error ? e.message : String(e)
    payState.value = 'error'
  }
}
</script>

<template>
  <div
    v-if="isPending || payState === 'paid'"
    class="rounded-xl border p-4 shadow-sm"
    :class="payState === 'paid' ? 'border-emerald-200 bg-emerald-50' : 'border-stone-200 bg-white'"
  >
    <div class="flex items-center justify-between gap-3">
      <div class="min-w-0">
        <p class="text-sm font-semibold text-stone-800">
          {{ payState === 'paid' ? '订单已确认' : '订单待支付' }}
        </p>
        <p class="mt-0.5 text-xs text-stone-500">订单号：{{ orderId }}</p>
        <p v-if="payError" class="mt-1 text-xs text-red-600">{{ payError }}</p>
      </div>

      <button
        v-if="isPending && payState !== 'paid'"
        type="button"
        class="shrink-0 rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors"
        :class="payState === 'paying' ? 'bg-stone-400 cursor-not-allowed' : 'bg-emerald-700 hover:bg-emerald-800'"
        :disabled="payState === 'paying'"
        @click="handlePay"
      >
        {{ payState === 'paying' ? '支付中…' : '去支付' }}
      </button>

      <span
        v-else-if="payState === 'paid'"
        class="shrink-0 inline-flex items-center gap-1 rounded-full bg-emerald-700 px-3 py-1 text-xs font-medium text-white"
      >
        <svg class="h-3.5 w-3.5" viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd" />
        </svg>
        已确认
      </span>
    </div>
  </div>
</template>
