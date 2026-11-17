<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { payOrder } from '@/api/tools'
import { getOrCreateUserId } from '@/api/chat'
import { useUserDataStore } from '@/stores/userData'
import { useChatStore } from '@/stores/chat'
import type { RoomCard } from '@/types/chat'

const props = defineProps<{
  /** Agent 回复文本（下单后含订单号 MS...） */
  content: string
  /** 后端下发的房源卡片（roomId→title/cover 映射），用于反查真实民宿名与图片。 */
  cards?: RoomCard[]
}>()

const userData = useUserDataStore()
const chatStore = useChatStore()

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

/**
 * 本次下单可用的房源卡片集合。
 *
 * 下单那轮 Agent 只调 create_order，不重新走 DECIDING，故下单消息自身 cards 为空；
 * 这里优先用消息自己的 cards，其次回退到会话内缓存的最近一次 DECIDING 卡片（chatStore.lastCards）。
 */
const availableCards = computed<RoomCard[]>(() => {
  const own = props.cards ?? []
  if (own.length > 0) return own
  return chatStore.lastCards ?? []
})

/**
 * 从文本反查本次下单对应的房源卡片。
 *
 * 下单房源一定是卡片里的某一套（主推第一套，或用户说「订候选2」的那套）。
 * 策略：先在文本里找「出现过的卡片标题关键词」命中；未命中则回退主推（第一套）。
 */
const resolvedRoom = computed<RoomCard | null>(() => {
  const cards = availableCards.value
  if (cards.length === 0) return null
  // 1. 精确：文本中出现某卡片标题（取标题里的核心词，去掉"·"前后缀也能匹配）
  for (const c of cards) {
    if (!c.title) continue
    const core = c.title.split('·').pop()?.trim() || c.title
    if (core && props.content.includes(core)) return c
  }
  // 2. 兜底：主推（第一套）
  return cards[0] ?? null
})

/** 提取民宿标题：优先反查卡片，其次文本【】/「」/「房源：」，最后才回退默认文案。 */
function extractTitle(): string {
  const room = resolvedRoom.value
  if (room?.title) return room.title
  // 「房源：老街里·白墙小院（庾村 · 原木）」这类下单成功文本
  const m = props.content.match(/房源[:：]\s*([^\s（(]+)/)
  if (m) return m[1]
  const m2 = props.content.match(/[【\[]([^】\]\[]+)[】\]]/)
  if (m2) return m2[1]
  const m3 = props.content.match(/[「「]([^」」]+)[」」]/)
  if (m3) return m3[1]
  return '民宿订单'
}

/** 提取民宿封面图：优先卡片 cover，其次文本里的图片 URL。 */
function extractImage(): string {
  const room = resolvedRoom.value
  if (room?.cover) return room.cover
  const m = props.content.match(/https?:\/\/[^\s)）]+\.(?:jpg|jpeg|png|webp)/i)
  return m ? m[0] : ''
}

/** 从订单文本提取总价（如「总价：**1224 元**」或「1224 元」）。 */
function extractTotal(): number {
  const m = props.content.match(/(?:总价|合计)[^\d]*(\d+)\s*元/)
  if (m) return Number(m[1])
  const m2 = props.content.match(/(\d{3,})\s*元/)
  return m2 ? Number(m2[1]) : 0
}

/** 从订单文本提取入住/离店日期（兼容「入住：2026-11-01 → 离店：2026-11-03」「2026-11-01 至 2026-11-03」等）。 */
function extractDates(): { checkIn: string; checkOut: string; nights: number } {
  // 兼容「入住：2026-11-01 → 离店：2026-11-03」：允许中间夹「离店：/入住：」等字样
  const m = props.content.match(/(\d{4}-\d{2}-\d{2})\s*(?:→|->|至|到|—|－)\s*(?:离店|入住|退房)?[:：]?\s*(\d{4}-\d{2}-\d{2})/)
  if (!m) return { checkIn: '', checkOut: '', nights: 1 }
  const a = new Date(m[1]).getTime()
  const b = new Date(m[2]).getTime()
  const nights = !isNaN(a) && !isNaN(b) && b > a ? Math.round((b - a) / 86400000) : 1
  return { checkIn: m[1], checkOut: m[2], nights }
}

/**
 * 挂载时恢复支付状态：若 store 里已存在该订单号且是「已支付」，
 * 说明此前已支付过（刷新/切页面导致组件内存态丢失），直接恢复为 paid，
 * 避免「退出再进又变回待支付」。
 */
onMounted(() => {
  if (!orderId.value) return
  const existing = userData.orders.find((o) => o.orderId === orderId.value)
  if (existing && existing.status !== '待支付') {
    payState.value = 'paid'
  }
})

async function handlePay() {
  if (!orderId.value || payState.value === 'paying') return
  payState.value = 'paying'
  payError.value = ''
  try {
    const resp = await payOrder(orderId.value, getOrCreateUserId())
    if (resp.success && resp.data && 'paid' in resp.data && resp.data.paid) {
      payState.value = 'paid'
      // 同步到本地订单 store，让「我的订单」页能看到这条 Agent 下单的订单
      const d = extractDates()
      userData.addOrder({
        orderId: orderId.value,
        homestayId: resolvedRoom.value?.roomId ?? '',
        homestayTitle: extractTitle(),
        homestayImage: extractImage(),
        checkInDate: d.checkIn,
        checkOutDate: d.checkOut,
        nights: d.nights,
        totalPrice: extractTotal(),
        contactPhone: '',
        status: '已支付',
      })
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
        <p class="mt-0.5 text-xs text-stone-600 truncate">{{ extractTitle() }}</p>
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
