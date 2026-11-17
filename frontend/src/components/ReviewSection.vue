<script setup lang="ts">
import { ref } from 'vue'
import type { Review } from '@/mock/data'
import StarRating from './StarRating.vue'

const props = defineProps<{
  /** 评价列表 */
  reviews: Review[]
  /** 标题，如「住客评价」「游客评价」 */
  title?: string
  /** 提交评价回调（由父组件持久化） */
  onSubmit: (payload: { user: string; score: number; content: string }) => void
}>()

const showForm = ref(false)
const formUser = ref('')
const formScore = ref(5)
const formContent = ref('')
const submitting = ref(false)

function resetForm(): void {
  formUser.value = ''
  formScore.value = 5
  formContent.value = ''
  showForm.value = false
}

function submit(): void {
  const user = formUser.value.trim()
  const content = formContent.value.trim()
  if (!content || formScore.value < 1) return
  submitting.value = true
  // 延迟一瞬模拟提交，让状态反馈可见
  setTimeout(() => {
    props.onSubmit({ user: user || '匿名用户', score: formScore.value, content })
    submitting.value = false
    resetForm()
  }, 200)
}

/** 平均分 */
const avg = computedAvg()
function computedAvg(): string {
  if (!props.reviews.length) return '—'
  const sum = props.reviews.reduce((acc, r) => acc + r.score, 0)
  return (sum / props.reviews.length).toFixed(1)
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-4">
      <div class="flex items-baseline gap-3">
        <h3 class="text-lg font-semibold text-stone-800">{{ title || '用户评价' }}</h3>
        <span class="text-sm text-stone-400">{{ reviews.length }} 条评价</span>
        <span v-if="reviews.length" class="flex items-center gap-1 text-sm text-amber-500 font-semibold">
          <svg viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4"><path d="M11.48 3.5c.31-.64 1.27-.64 1.58 0l1.79 3.63 4 .58c.71.1.99.97.48 1.47l-2.89 2.82.68 3.98c.12.7-.62 1.24-1.25.9L12 14.62l-3.57 1.88c-.63.33-1.37-.2-1.25-.9l.68-3.98-2.89-2.82c-.51-.5-.23-1.37.48-1.47l4-.58 1.79-3.63z" /></svg>
          {{ avg }}
        </span>
      </div>
      <button
        v-if="!showForm"
        type="button"
        class="px-3 py-1.5 rounded-lg bg-emerald-700 text-white text-sm font-medium hover:bg-emerald-800 transition-colors"
        @click="showForm = true"
      >
        写评价
      </button>
    </div>

    <!-- 评价表单 -->
    <div v-if="showForm" class="mb-4 p-4 rounded-xl bg-emerald-50/60 border border-emerald-100">
      <div class="flex items-center gap-3 mb-3">
        <span class="text-sm text-stone-600">你的评分</span>
        <StarRating v-model="formScore" interactive :size="'md'" />
        <span class="text-sm text-amber-600 font-medium">{{ formScore }} 星</span>
      </div>
      <div class="mb-3">
        <input
          v-model="formUser"
          type="text"
          maxlength="20"
          placeholder="昵称（可选，默认匿名用户）"
          class="w-full h-10 px-3 rounded-lg border border-stone-200 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500"
        />
      </div>
      <div class="mb-3">
        <textarea
          v-model="formContent"
          rows="3"
          maxlength="500"
          placeholder="分享你的真实入住/游玩感受吧…"
          class="w-full px-3 py-2.5 rounded-lg border border-stone-200 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500"
        ></textarea>
      </div>
      <div class="flex items-center justify-end gap-2">
        <button
          type="button"
          class="px-3 py-1.5 rounded-lg border border-stone-200 text-stone-600 text-sm hover:border-stone-300"
          @click="resetForm"
        >
          取消
        </button>
        <button
          type="button"
          class="px-4 py-1.5 rounded-lg bg-emerald-700 text-white text-sm font-medium hover:bg-emerald-800 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
          :disabled="!formContent.trim() || submitting"
          @click="submit"
        >
          {{ submitting ? '提交中…' : '提交评价' }}
        </button>
      </div>
    </div>

    <!-- 评价列表 -->
    <div v-if="reviews.length" class="space-y-4">
      <div v-for="r in reviews" :key="r.id" class="py-4 border-b border-stone-100 last:border-0">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="w-8 h-8 rounded-full bg-emerald-100 flex items-center justify-center text-emerald-700 text-sm font-semibold">{{ r.user.slice(0, 1) }}</span>
            <span class="font-medium text-stone-800">{{ r.user }}</span>
          </div>
          <StarRating :model-value="r.score" readonly :size="'sm'" />
        </div>
        <p class="mt-2 text-stone-600 text-sm leading-relaxed">{{ r.content }}</p>
        <p class="mt-1 text-xs text-stone-400">{{ r.date }}</p>
      </div>
    </div>
    <p v-else class="py-6 text-center text-sm text-stone-400">还没有评价，来抢沙发～</p>
  </div>
</template>
