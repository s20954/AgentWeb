<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    /** 当前星级（1-5） */
    modelValue: number
    /** 是否只读（仅展示） */
    readonly?: boolean
    /** 是否可交互（悬停预览 + 点击选择） */
    interactive?: boolean
    size?: 'sm' | 'md'
  }>(),
  { readonly: false, interactive: false, size: 'md' },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: number): void
}>()

const hover = defineModel<number>('hover', { default: 0 })

function setValue(v: number): void {
  if (props.readonly || !props.interactive) return
  emit('update:modelValue', v)
}

const starCls = props.size === 'sm' ? 'w-4 h-4' : 'w-5 h-5'
</script>

<template>
  <div class="flex items-center gap-0.5">
    <svg
      v-for="s in 5"
      :key="s"
      :viewBox="'0 0 24 24'"
      :class="[starCls, 'cursor-pointer transition-colors', s <= (hover || modelValue) ? 'text-amber-500' : 'text-stone-300']"
      :fill="s <= (hover || modelValue) ? 'currentColor' : 'none'"
      stroke="currentColor"
      stroke-width="1.5"
      @mouseenter="interactive && (hover = s)"
      @mouseleave="interactive && (hover = 0)"
      @click="setValue(s)"
    >
      <path d="M11.48 3.5c.31-.64 1.27-.64 1.58 0l1.79 3.63 4 .58c.71.1.99.97.48 1.47l-2.89 2.82.68 3.98c.12.7-.62 1.24-1.25.9L12 14.62l-3.57 1.88c-.63.33-1.37-.2-1.25-.9l.68-3.98-2.89-2.82c-.51-.5-.23-1.37.48-1.47l4-.58 1.79-3.63z" />
    </svg>
  </div>
</template>
