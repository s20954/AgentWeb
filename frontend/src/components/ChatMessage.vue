<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '@/types/chat'
import ThinkingChain from './ThinkingChain.vue'

const props = defineProps<{
  message: ChatMessage
}>()

const isUser = computed(() => props.message.role === 'user')

/** 把换行文本渲染成段落（保留换行）。 */
const paragraphs = computed(() =>
  props.message.content.split('\n').filter((p) => p.trim() !== ''),
)
</script>

<template>
  <div class="flex" :class="isUser ? 'justify-end' : 'justify-start'">
    <div
      class="max-w-[85%] sm:max-w-[75%]"
    >
      <!-- 用户气泡 -->
      <div
        v-if="isUser"
        class="rounded-2xl rounded-br-sm bg-indigo-600 text-white px-4 py-2.5 text-sm whitespace-pre-wrap"
      >
        {{ message.content }}
      </div>

      <!-- Agent 气泡（含思考链 + 文本） -->
      <div v-else class="space-y-3">
        <ThinkingChain v-if="message.steps && message.steps.length" :steps="message.steps" />

        <div
          v-if="message.content"
          class="rounded-2xl rounded-bl-sm bg-white border border-stone-200 px-4 py-3 text-sm text-stone-800 shadow-sm"
        >
          <p v-for="(p, i) in paragraphs" :key="i" class="whitespace-pre-wrap" :class="{ 'mt-2': i > 0 }">
            {{ p }}
          </p>
          <span
            v-if="message.streaming"
            class="inline-block w-2 h-4 ml-0.5 align-text-bottom bg-stone-400 animate-pulse"
          ></span>
        </div>

        <!-- 流式加载态（还没文本时的骨架） -->
        <div
          v-else-if="message.streaming"
          class="rounded-2xl rounded-bl-sm bg-white border border-stone-200 px-4 py-3 shadow-sm flex items-center gap-1.5"
        >
          <span class="w-2 h-2 rounded-full bg-stone-300 animate-bounce"></span>
          <span class="w-2 h-2 rounded-full bg-stone-300 animate-bounce [animation-delay:0.15s]"></span>
          <span class="w-2 h-2 rounded-full bg-stone-300 animate-bounce [animation-delay:0.3s]"></span>
        </div>
      </div>
    </div>
  </div>
</template>
