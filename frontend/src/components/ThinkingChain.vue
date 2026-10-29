<script setup lang="ts">
import { computed } from 'vue'
import type { StepEvent, StepType } from '@/types/chat'

const props = defineProps<{
  steps: StepEvent[]
}>()

/** 七步收敛的完整序列（顺序 + 展示名），用于渲染时间线骨架。 */
const STEP_ORDER: { type: StepType; label: string }[] = [
  { type: 'EXTRACTING', label: '理解需求' },
  { type: 'GAP_FOUND', label: '识别缺口' },
  { type: 'ASKING', label: '带着方案问' },
  { type: 'LOADING_KNOWLEDGE', label: '加载片区知识' },
  { type: 'FILTERING', label: '筛选房源' },
  { type: 'PACKAGING', label: '包装方案' },
  { type: 'DECIDING', label: '给出建议' },
]

/** 已到达的步骤类型集合。 */
const reachedTypes = computed(() => new Set(props.steps.map((s) => s.type)))

/** 当前最后一步（正在执行/最新完成的）。 */
const lastType = computed<StepType | null>(
  () => props.steps[props.steps.length - 1]?.type ?? null,
)

/** 某步是否已完成（出现在 steps 中且不是最后一步）。 */
function isDone(type: StepType): boolean {
  return reachedTypes.value.has(type) && lastType.value !== type
}

/** 某步是否正在进行（是最后一步）。 */
function isActive(type: StepType): boolean {
  return lastType.value === type
}

/** 某步的文案。 */
function labelOf(type: StepType): string {
  const found = props.steps.find((s) => s.type === type)
  return found?.message ?? STEP_ORDER.find((s) => s.type === type)?.label ?? ''
}
</script>

<template>
  <div class="thinking-chain rounded-xl border border-indigo-100 bg-indigo-50/50 p-4">
    <div class="flex items-center gap-2 text-xs font-medium text-indigo-600">
      <span class="inline-flex items-center justify-center w-5 h-5 rounded-full bg-indigo-100 text-indigo-600">
        <svg viewBox="0 0 24 24" fill="none" class="w-3.5 h-3.5" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456Z" />
        </svg>
      </span>
      Agent 思考过程
    </div>

    <ol class="mt-3 space-y-2">
      <li
        v-for="(step, i) in STEP_ORDER"
        :key="step.type"
        class="flex items-start gap-3 transition-opacity"
        :class="[isDone(step.type) || isActive(step.type) ? 'opacity-100' : 'opacity-40']"
      >
        <!-- 节点 -->
        <span
          class="relative mt-0.5 flex items-center justify-center w-5 h-5 rounded-full text-[10px] font-semibold shrink-0"
          :class="
            isDone(step.type)
              ? 'bg-emerald-500 text-white'
              : isActive(step.type)
                ? 'bg-indigo-500 text-white animate-pulse'
                : 'bg-stone-200 text-stone-500'
          "
        >
          <svg v-if="isDone(step.type)" viewBox="0 0 24 24" fill="none" class="w-3 h-3" stroke="currentColor" stroke-width="3">
            <path stroke-linecap="round" stroke-linejoin="round" d="m4.5 12.75 6 6 9-13.5" />
          </svg>
          <span v-else>{{ i + 1 }}</span>
        </span>

        <!-- 文案 -->
        <div class="min-w-0">
          <div class="text-sm" :class="isActive(step.type) ? 'text-indigo-700 font-medium' : 'text-stone-700'">
            {{ labelOf(step.type) }}
          </div>
        </div>
      </li>
    </ol>
  </div>
</template>
