<script setup lang="ts">
/**
 * 文本输入框 —— 通过 v-model 绑定文本，父组件可注入语音/看图选房识别结果。
 * 回车或点击发送按钮触发 emit('send', text)。
 * 内置「联网搜索」开关（DeepSeek 风格：输入框左侧的开关按钮）。
 */
const text = defineModel<string>({ default: '' })

const props = defineProps<{
  loading: boolean
  disabled?: boolean
  /** 是否开启「联网搜索」模式。 */
  webSearch: boolean
}>()

const emit = defineEmits<{
  (e: 'send', text: string, webSearch: boolean): void
  (e: 'toggle-web-search'): void
}>()

function submit(): void {
  const value = text.value.trim()
  if (!value || props.loading) return
  emit('send', value, props.webSearch)
  text.value = ''
}
</script>

<template>
  <div>
    <div class="flex items-end gap-2">
      <!-- 联网搜索开关（DeepSeek 风格：输入框左侧） -->
      <button
        type="button"
        :class="[
          'shrink-0 inline-flex items-center gap-1 rounded-xl px-3 py-2.5 text-sm transition-colors border',
          webSearch
            ? 'bg-indigo-600 border-indigo-600 text-white'
            : 'bg-white border-stone-200 text-stone-500 hover:border-indigo-300 hover:text-indigo-600',
        ]"
        :title="webSearch ? '已开启联网搜索，点击关闭' : '开启联网搜索'"
        @click="emit('toggle-web-search')"
      >
        <!-- 地球图标 -->
        <svg viewBox="0 0 24 24" fill="none" class="w-4 h-4" stroke="currentColor" stroke-width="1.6">
          <circle cx="12" cy="12" r="9" />
          <path stroke-linecap="round" d="M3 12h18M12 3c2.5 2.67 3.75 5.5 3.75 9S14.5 18.33 12 21c-2.5-2.67-3.75-5.5-3.75-9S9.5 5.67 12 3z" />
        </svg>
        <span class="hidden sm:inline">联网搜索</span>
      </button>

      <textarea
        v-model="text"
        :disabled="disabled"
        rows="1"
        :placeholder="webSearch
          ? '联网搜索：先查知识库，没有就上网搜（如：莫干山红叶最佳观赏期）'
          : '比如：莫干山，11月1日到3日，预算每晚800，情侣'"
        class="flex-1 resize-none rounded-xl border border-stone-200 bg-stone-50 px-4 py-2.5 text-sm text-stone-800 placeholder:text-stone-400 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:border-transparent disabled:opacity-50"
        @keydown.enter.exact.prevent="submit"
      ></textarea>

      <button
        type="button"
        :disabled="loading || disabled || !text.trim()"
        class="shrink-0 inline-flex items-center justify-center rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        @click="submit"
      >
        <svg v-if="loading" class="w-4 h-4 animate-spin" viewBox="0 0 24 24" fill="none">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
        </svg>
        <span v-else>{{ webSearch ? '搜索' : '发送' }}</span>
      </button>
    </div>
    <p class="mt-1.5 text-xs text-stone-400">
      {{ webSearch ? '联网搜索模式：优先查本地知识库，未命中再联网整合' : 'Enter 发送 · Agent 会一步步收敛并给你一个可决策的方案' }}
    </p>
  </div>
</template>