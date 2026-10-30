<script setup lang="ts">
import { computed } from 'vue'
import { marked, Renderer, type Tokens } from 'marked'
import type { ChatMessage } from '@/types/chat'
import ThinkingChain from './ThinkingChain.vue'
import RoomCards from './RoomCards.vue'

const props = defineProps<{
  message: ChatMessage
}>()

const isUser = computed(() => props.message.role === 'user')

/**
 * 消毒链接 href：marked 只做解析不做消毒，LLM/联网内容可构造
 * [x](javascript:alert(1)) 这类危险协议链接。浏览器解析 href 前会先解码 HTML 实体，
 * 所以检查前先解码实体并去掉空白/控制字符，防 `&#106;avascript:`、`java\tscript:` 绕过。
 */
function sanitizeHref(href: string): string {
  const decoded = href
    .replace(/&#x([0-9a-fA-F]+);?/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);?/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
  const normalized = decoded.replace(/[\s\u0000-\u001f]+/g, '')
  if (/^(javascript|vbscript|data):/i.test(normalized)) {
    return ''
  }
  return href
}

// 自定义链接渲染：危险协议只渲染文本（不可点击）；正常链接新窗口打开
const renderer = new Renderer()
renderer.link = function (this: Renderer, { href, title, tokens }: Tokens.Link): string {
  const text = this.parser.parseInline(tokens)
  const safe = sanitizeHref(href ?? '')
  if (!safe) {
    return text
  }
  const titleAttr = title ? ` title="${title}"` : ''
  return `<a href="${safe}"${titleAttr} target="_blank" rel="noopener noreferrer">${text}</a>`
}
marked.use({ renderer })

/**
 * Agent 回复按 Markdown 渲染（标题/加粗/列表/引用/链接）。
 * 内容来自后端 LLM，先把原始 HTML 标签转义掉再解析，配合链接消毒防注入。
 */
const rendered = computed(() => {
  const safe = props.message.content.replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return marked.parse(safe, { async: false }) as string
})
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

      <!-- Agent 气泡（含思考链 + Markdown 文本） -->
      <div v-else class="space-y-3">
        <ThinkingChain v-if="message.steps && message.steps.length" :steps="message.steps" />

        <RoomCards v-if="message.cards && message.cards.length" :cards="message.cards" :booking="message.booking" />

        <div
          v-if="message.content"
          class="rounded-2xl rounded-bl-sm bg-white border border-stone-200 px-4 py-3 text-sm text-stone-800 shadow-sm"
        >
          <div class="md-body" v-html="rendered"></div>
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

<!-- Markdown 排版（scoped 无法命中 v-html，用 :deep） -->
<style scoped>
.md-body {
  word-break: break-word;
  overflow-wrap: anywhere;
}
.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3),
.md-body :deep(h4) {
  font-weight: 700;
  color: #1c1917;
  margin: 0.9em 0 0.4em;
  line-height: 1.35;
}
.md-body :deep(h1) { font-size: 1.15rem; }
.md-body :deep(h2) { font-size: 1.05rem; }
.md-body :deep(h3) { font-size: 0.95rem; }
.md-body :deep(h1:first-child),
.md-body :deep(h2:first-child),
.md-body :deep(h3:first-child) {
  margin-top: 0;
}
.md-body :deep(p) {
  margin: 0.45em 0;
  line-height: 1.7;
}
.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 0.45em 0;
  padding-left: 1.3em;
  line-height: 1.7;
}
.md-body :deep(ul) { list-style: disc; }
.md-body :deep(ol) { list-style: decimal; }
.md-body :deep(li) { margin: 0.15em 0; }
.md-body :deep(strong) { font-weight: 600; color: #1c1917; }
.md-body :deep(em) { color: #78716c; }
.md-body :deep(blockquote) {
  border-left: 3px solid #d6d3d1;
  padding-left: 0.8em;
  margin: 0.6em 0;
  color: #57534e;
}
.md-body :deep(code) {
  background: #f5f5f4;
  border-radius: 4px;
  padding: 0.1em 0.35em;
  font-size: 0.85em;
}
.md-body :deep(pre) {
  background: #f5f5f4;
  border-radius: 8px;
  padding: 0.7em 0.9em;
  overflow-x: auto;
  margin: 0.6em 0;
}
.md-body :deep(pre code) {
  background: transparent;
  padding: 0;
}
.md-body :deep(a) {
  color: #4f46e5;
  text-decoration: underline;
  word-break: break-all;
}
.md-body :deep(hr) {
  border: none;
  border-top: 1px solid #e7e5e4;
  margin: 0.8em 0;
}
</style>