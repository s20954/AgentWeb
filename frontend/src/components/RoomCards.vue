<script setup lang="ts">
import { computed, ref } from 'vue'
import type { BookingContext, RoomCard } from '@/types/chat'
import { quotePrice, type QuoteData } from '@/api/tools'
import { useChatStore } from '@/stores/chat'

const props = defineProps<{
  cards: RoomCard[]
  booking?: BookingContext
}>()

const chatStore = useChatStore()

/** 主推 = 第一张（后端筛选顺序保证第一个是主推）。 */
const recommendedId = computed(() => props.cards[0]?.roomId ?? '')

/** 风格 → 图替身渐变配色（真实图加载失败时回退用，浅色商务风下保持协调）。 */
const STYLE_GRADIENT: Record<string, string> = {
  原木: 'from-amber-200 to-stone-200',
  溪流: 'from-cyan-200 to-sky-200',
  设计: 'from-indigo-200 to-violet-200',
  亲子: 'from-emerald-200 to-teal-200',
  网红: 'from-pink-200 to-fuchsia-200',
}

function gradientOf(style: string): string {
  return STYLE_GRADIENT[style] ?? 'from-slate-200 to-slate-100'
}

/** 图片加载失败 → 回退风格渐变（记录到哪个 roomId 挂了）。 */
const imgFailed = ref<Record<string, boolean>>({})
function onImgError(roomId: string) {
  imgFailed.value[roomId] = true
}

/** 每套房源的报价状态。 */
interface QuoteState {
  loading: boolean
  data: QuoteData | null
  error: string | null
}
const quoteMap = ref<Record<string, QuoteState>>({})
const EMPTY_QUOTE: QuoteState = { loading: false, data: null, error: null }
function quoteOf(id: string): QuoteState {
  return quoteMap.value[id] ?? EMPTY_QUOTE
}

/** 点「帮我报价」：调后端 quote_price，明细留在该卡片内。 */
async function runQuote(room: RoomCard) {
  if (!props.booking) return
  quoteMap.value[room.roomId] = { loading: true, data: null, error: null }
  try {
    const resp = await quotePrice(
      {
        roomId: room.roomId,
        checkIn: props.booking.checkIn,
        checkOut: props.booking.checkOut,
        guests: props.booking.guests,
      },
      chatStore.sessionId,
    )
    if (resp.success && resp.data && 'total' in resp.data) {
      quoteMap.value[room.roomId] = { loading: false, data: resp.data, error: null }
    } else {
      const msg =
        (resp.data && 'message' in resp.data && resp.data.message) || resp.note || '报价失败，请稍后重试'
      quoteMap.value[room.roomId] = { loading: false, data: null, error: msg }
    }
  } catch (e) {
    quoteMap.value[room.roomId] = {
      loading: false,
      data: null,
      error: e instanceof Error ? e.message : String(e),
    }
  }
}
</script>

<template>
  <div class="grid gap-3 sm:grid-cols-3">
    <div
      v-for="room in cards"
      :key="room.roomId"
      class="relative flex flex-col rounded-2xl border bg-white shadow-sm overflow-hidden hover:shadow-md transition-shadow"
      :class="room.roomId === recommendedId ? 'border-indigo-400 ring-2 ring-indigo-100' : 'border-stone-200'"
    >
      <!-- 主推角标 -->
      <span
        v-if="room.roomId === recommendedId"
        class="absolute top-2 left-2 z-10 rounded-full bg-indigo-600 text-white text-xs px-2 py-0.5 font-medium"
      >
        主推
      </span>

      <!-- 封面图（真实图，加载失败回退风格渐变） -->
      <div class="relative h-28 bg-gradient-to-br" :class="gradientOf(room.style)">
        <img
          v-if="room.cover && !imgFailed[room.roomId]"
          :src="room.cover"
          :alt="room.title"
          class="absolute inset-0 h-full w-full object-cover"
          loading="lazy"
          @error="onImgError(room.roomId)"
        />
        <div v-else class="absolute inset-0 flex items-center justify-center">
          <svg viewBox="0 0 24 24" fill="none" class="w-10 h-10 text-stone-500/60" stroke="currentColor" stroke-width="1.4">
            <path stroke-linecap="round" stroke-linejoin="round" d="m2.25 12 8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75" />
          </svg>
        </div>
        <span class="absolute bottom-2 right-2 bg-white/85 text-stone-700 rounded-full text-xs px-2 py-0.5">
          {{ room.area }} · {{ room.style }}
        </span>
      </div>

      <div class="p-3 flex-1 flex flex-col">
        <div class="flex items-start justify-between gap-2">
          <h4 class="font-semibold text-stone-900 text-sm leading-snug">{{ room.title }}</h4>
          <div class="shrink-0 text-right">
            <span class="text-indigo-600 font-bold text-base">¥{{ room.basePrice }}</span>
            <span class="text-xs text-stone-500">/晚起</span>
          </div>
        </div>

        <p class="mt-1.5 text-xs text-stone-600">{{ room.positioning }}</p>

        <div class="mt-2 space-y-1 text-xs leading-5">
          <p class="text-stone-700">
            <span class="text-emerald-600 font-medium">适合</span>
            {{ room.fitReason }}
          </p>
          <p class="text-stone-500">
            <span class="text-amber-600 font-medium">注意</span>
            {{ room.drawback }}
          </p>
        </div>

        <!-- 报价区（让评委能一路点到底） -->
        <div class="mt-auto pt-3">
          <button
            class="w-full rounded-lg bg-indigo-600 text-white text-xs py-2 font-medium hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="!booking || quoteOf(room.roomId).loading"
            @click="runQuote(room)"
          >
            <span v-if="quoteOf(room.roomId).loading">报价中…</span>
            <span v-else-if="quoteOf(room.roomId).data">已报价 · 查看明细</span>
            <span v-else>帮我报价</span>
          </button>

          <div
            v-if="quoteOf(room.roomId).data"
            class="mt-2 rounded-lg bg-stone-50 border border-stone-200 p-2 text-xs space-y-1"
          >
            <div class="flex justify-between text-stone-600">
              <span>房费 ×{{ quoteOf(room.roomId).data?.nights }} 晚</span>
              <span>¥{{ quoteOf(room.roomId).data?.fee.roomFee }}</span>
            </div>
            <div class="flex justify-between text-stone-600">
              <span>清洁费</span>
              <span>¥{{ quoteOf(room.roomId).data?.fee.cleaning }}</span>
            </div>
            <div class="flex justify-between text-stone-600">
              <span>服务费（10%）</span>
              <span>¥{{ quoteOf(room.roomId).data?.fee.service }}</span>
            </div>
            <div class="flex justify-between font-semibold text-stone-900 border-t border-stone-200 pt-1">
              <span>含全部费用</span>
              <span class="text-indigo-600">¥{{ quoteOf(room.roomId).data?.total }}</span>
            </div>
          </div>

          <p v-else-if="quoteOf(room.roomId).error" class="mt-2 text-xs text-red-600">
            {{ quoteOf(room.roomId).error }}
          </p>
        </div>
      </div>
    </div>
  </div>
</template>