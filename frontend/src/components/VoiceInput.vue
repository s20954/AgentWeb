<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 语音输入按钮 —— 基于浏览器 Web Speech API（纯前端，不依赖后端）。
 *
 * 点击后调用 webkitSpeechRecognition / SpeechRecognition，中文识别（zh-CN），
 * 识别结果通过 emit('recognized', text) 抛给父组件填入输入框（不自动发送）。
 * 不支持的浏览器（非 Chrome/Edge）按钮置灰并给 tooltip。
 */
const emit = defineEmits<{
  (e: 'recognized', text: string): void
}>()

const listening = ref(false)
const supported = ref(false)

let recognition: any = null

/** 取当前浏览器可用的识别构造器（标准名优先，兼容 webkit 前缀）。 */
function getRecognitionCtor(): any {
  const w = window as any
  return w.SpeechRecognition || w.webkitSpeechRecognition || null
}

onMounted(() => {
  supported.value = !!getRecognitionCtor()
})

function toggle(): void {
  if (!supported.value) return
  if (listening.value) {
    recognition?.stop()
    return
  }

  const Ctor = getRecognitionCtor()
  if (!Ctor) return
  recognition = new Ctor()
  recognition.lang = 'zh-CN'
  recognition.continuous = false
  recognition.interimResults = false

  recognition.onresult = (event: any) => {
    let transcript = ''
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const res = event.results[i]
      if (res.isFinal) transcript += res[0].transcript
    }
    if (transcript.trim()) {
      emit('recognized', transcript.trim())
    }
  }
  recognition.onerror = () => {
    listening.value = false
  }
  recognition.onend = () => {
    listening.value = false
  }

  listening.value = true
  try {
    recognition.start()
  } catch {
    listening.value = false
  }
}

onBeforeUnmount(() => {
  if (listening.value) recognition?.stop()
})
</script>

<template>
  <button
    type="button"
    :disabled="!supported"
    :title="supported ? '点击开始语音输入' : '当前浏览器不支持语音识别（请用 Chrome/Edge）'"
    class="relative inline-flex items-center justify-center w-9 h-9 rounded-xl border transition-colors"
    :class="
      listening
        ? 'bg-red-50 border-red-300 text-red-600'
        : supported
          ? 'bg-white border-stone-200 text-stone-500 hover:border-indigo-300 hover:text-indigo-600'
          : 'bg-stone-50 border-stone-200 text-stone-300 cursor-not-allowed'
    "
    @click="toggle"
  >
    <!-- 识别中的脉冲光环 -->
    <span
      v-if="listening"
      class="absolute inset-0 rounded-xl border-2 border-red-400 voice-ping"
    ></span>
    <svg viewBox="0 0 24 24" fill="none" class="w-4 h-4" stroke="currentColor" stroke-width="1.8">
      <path stroke-linecap="round" stroke-linejoin="round" d="M12 18.75a6 6 0 0 0 6-6v-1.5m-6 7.5a6 6 0 0 1-6-6v-1.5m6 7.5v3.75m-3.75 0h7.5M12 15.75a3 3 0 0 1-3-3V4.5a3 3 0 1 1 6 0v8.25a3 3 0 0 1-3 3Z" />
    </svg>
  </button>
</template>

<style scoped>
.voice-ping {
  animation: voice-ping 1s cubic-bezier(0, 0, 0.2, 1) infinite;
}
@keyframes voice-ping {
  0% {
    transform: scale(1);
    opacity: 0.8;
  }
  100% {
    transform: scale(1.6);
    opacity: 0;
  }
}
</style>
