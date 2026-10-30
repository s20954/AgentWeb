<script setup lang="ts">
import { ref } from 'vue'

/**
 * 看图选房按钮 —— 选图后本地压缩转 base64，调 /api/vision/analyze，
 * 把返回的风格标签回填成一句自然语言 emit('recognized', text) 抛给父组件，
 * 同时展示「识别到：设计风 / 预算 800-1500」这样的可解释中间结果。
 */
const emit = defineEmits<{
  (e: 'recognized', text: string): void
}>()

const loading = ref(false)
const result = ref('')
const error = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

function pick(): void {
  fileInput.value?.click()
}

async function onFileChange(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  loading.value = true
  error.value = ''
  result.value = ''
  try {
    const { imageBase64, mimeType } = await compressToBase64(file)
    const res = await fetch('/api/vision/analyze', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ imageBase64, mimeType }),
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data = await res.json()
    const summary = buildSummary(data)
    result.value = summary
    emit('recognized', summary)
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  } finally {
    loading.value = false
    if (input) input.value = ''
  }
}

/**
 * 读取图片并压缩：canvas 压到宽 ≤1024、转 JPEG，避免 base64 过大。
 */
function compressToBase64(file: File): Promise<{ imageBase64: string; mimeType: string }> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const img = new Image()
      img.onload = () => {
        const maxW = 1024
        const scale = Math.min(1, maxW / img.width)
        const w = Math.round(img.width * scale)
        const h = Math.round(img.height * scale)
        const canvas = document.createElement('canvas')
        canvas.width = w
        canvas.height = h
        const ctx = canvas.getContext('2d')
        if (!ctx) {
          reject(new Error('当前浏览器不支持 canvas'))
          return
        }
        ctx.drawImage(img, 0, 0, w, h)
        const dataUrl = canvas.toDataURL('image/jpeg', 0.85)
        const base64 = dataUrl.split(',')[1] ?? ''
        resolve({ imageBase64: base64, mimeType: 'image/jpeg' })
      }
      img.onerror = () => reject(new Error('图片加载失败'))
      img.src = reader.result as string
    }
    reader.onerror = () => reject(new Error('文件读取失败'))
    reader.readAsDataURL(file)
  })
}

/** 把后端返回标签拼成一句自然语言。 */
function buildSummary(data: any): string {
  const styles = Array.isArray(data.styles) ? data.styles.join(' / ') : ''
  const budget = data.budget || ''
  const keywords = Array.isArray(data.keywords) ? data.keywords.join('、') : ''
  const parts: string[] = []
  if (styles) parts.push(`${styles}风`)
  if (budget) parts.push(`预算 ${budget}`)
  if (keywords) parts.push(`关键词 ${keywords}`)
  return parts.length ? `识别到：${parts.join(' / ')}` : '已识别图片偏好'
}
</script>

<template>
  <div class="inline-flex items-start gap-2">
    <button
      type="button"
      :disabled="loading"
      class="inline-flex items-center gap-1.5 rounded-xl border border-stone-200 bg-white px-3 py-2 text-xs text-stone-500 hover:border-indigo-300 hover:text-indigo-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
      @click="pick"
    >
      <svg
        v-if="loading"
        class="w-3.5 h-3.5 animate-spin"
        viewBox="0 0 24 24"
        fill="none"
      >
        <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
        <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
      </svg>
      <svg v-else viewBox="0 0 24 24" fill="none" class="w-3.5 h-3.5" stroke="currentColor" stroke-width="1.8">
        <path stroke-linecap="round" stroke-linejoin="round" d="m2.25 15.75 5.159-5.159a2.25 2.25 0 0 1 3.182 0l5.159 5.159m-1.5-1.5 1.409-1.409a2.25 2.25 0 0 1 3.182 0l2.909 2.909M3.75 21h16.5A1.5 1.5 0 0 0 21.75 19.5V4.5A1.5 1.5 0 0 0 20.25 3H3.75A1.5 1.5 0 0 0 2.25 4.5v15A1.5 1.5 0 0 0 3.75 21Zm10.5-11.25h.008v.008h-.008V9.75Z" />
      </svg>
      <span>{{ loading ? '识别中…' : '看图选房' }}</span>
    </button>

    <input ref="fileInput" type="file" accept="image/*" class="hidden" @change="onFileChange" />

    <!-- 可解释的中间展示 -->
    <div v-if="result || error" class="text-xs leading-5">
      <p v-if="result" class="text-indigo-600">{{ result }}</p>
      <p v-if="error" class="text-red-500">识别失败：{{ error }}</p>
    </div>
  </div>
</template>