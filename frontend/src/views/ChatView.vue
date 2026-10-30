<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { webSearch } from '@/api/search'
import ChatMessage from '@/components/ChatMessage.vue'
import ChatInput from '@/components/ChatInput.vue'
import VoiceInput from '@/components/VoiceInput.vue'
import ImageSelect from '@/components/ImageSelect.vue'

const router = useRouter()

const store = useChatStore()
const listEl = ref<HTMLElement | null>(null)
const inputText = ref('')

/** 联网搜索开关（输入框旁，DeepSeek 风格）。 */
const webSearchOn = ref(false)
const searching = ref(false)

/** 语音 / 看图选房识别结果填入输入框（不自动发送，用户可改后手动回车）。 */
function fillInput(text: string): void {
  inputText.value = text
}

/** 输入框发送：根据开关决定走普通对话还是智能检索。 */
async function handleSend(text: string, webSearch: boolean): Promise<void> {
  if (webSearch) {
    await doSearch(text)
  } else {
    store.send(text)
  }
}

/** 执行智能检索（先查知识库 → 再联网 → 整合）：思考链逐步上屏 + 结果打字机流式输出。 */
async function doSearch(q: string): Promise<void> {
  if (!q || searching.value) return
  searching.value = true
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

  // 1. 用户消息上屏（与普通对话一致）
  store.messages.push({ id: `u-${Date.now()}`, role: 'user', content: q })

  // 2. 先建空 Agent 消息（streaming 态），思考链随进度逐步追加
  const agentId = `search-${Date.now()}`
  store.messages.push({ id: agentId, role: 'agent', content: '', streaming: true, steps: [] })
  // 通过 store 内的响应式元素更新（直接改局部对象引用 Vue 检测不到）
  const agentMsg = () => store.messages.find((m) => m.id === agentId)!
  agentMsg().steps = [{ type: 'EXTRACTING', message: `理解需求「${q}」…` }]

  try {
    const resp = await webSearch(q, store.sessionId)

    // 关键信息不全 → 后端在追问：展示「识别缺口 → 带着方案问」，然后输出问题
    if (resp.needClarification) {
      agentMsg().steps = [
        ...agentMsg().steps!,
        { type: 'GAP_FOUND', message: `识别到关键信息缺口：${(resp.missingFields || []).join('、')}` },
      ]
      await sleep(400)
      agentMsg().steps = [...agentMsg().steps!, { type: 'ASKING', message: '信息不全，先向你确认，再出方案' }]
      await sleep(400)
      await typewriter(agentMsg, resp.answer || '需要补充一些信息。')
      return
    }

    // 信息齐全 → 依次补「是否联网」「整合」两步，带小延时让过程可见
    agentMsg().steps = [
      ...agentMsg().steps!,
      { type: 'FILTERING', message: resp.primarySource === 'kb' ? '知识库已命中，无需联网' : '知识库未命中，转联网搜索…' },
    ]
    await sleep(350)
    agentMsg().steps = [...agentMsg().steps!, { type: 'PACKAGING', message: '整合知识库与联网结果…' }]
    await sleep(400)

    let content: string
    if (resp.error) {
      content = `（智能检索失败：${resp.message || resp.error}）`
    } else {
      content = resp.answer || '未找到相关信息，试试换个关键词。'
      if (resp.sources && resp.sources.length > 0) {
        content += `\n\n_来源：${resp.sources.join(' + ')}_`
      }
    }
    await typewriter(agentMsg, content)
  } catch {
    agentMsg().content = '（智能检索请求失败，请稍后重试）'
  } finally {
    agentMsg().streaming = false
    searching.value = false
  }
}

/** 打字机式流式输出：逐段把内容追加到指定消息。 */
async function typewriter(agentMsg: () => { content: string }, content: string): Promise<void> {
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
  const CHUNK = 12
  for (let i = 0; i < content.length; i += CHUNK) {
    agentMsg().content += content.slice(i, i + CHUNK)
    await sleep(24)
  }
}

/** 消息变化时滚动到底部。 */
async function scrollToBottom(): Promise<void> {
  await nextTick()
  if (listEl.value) {
    listEl.value.scrollTop = listEl.value.scrollHeight
  }
}

watch(
  () => store.messages.map((m) => m.content).join('|'),
  () => scrollToBottom(),
  { deep: true },
)

watch(() => store.messages.length, () => scrollToBottom())
</script>

<template>
  <div class="h-screen flex flex-col bg-stone-50">
    <!-- 顶栏 -->
    <header class="shrink-0 border-b border-stone-200 bg-white px-4 py-3 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <button
          type="button"
          class="flex items-center gap-1 text-xs text-stone-500 hover:text-indigo-600 transition-colors"
          @click="router.push('/home')"
        >
          <svg viewBox="0 0 24 24" fill="none" class="w-4 h-4" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 19.5L8.25 12l7.5-7.5" />
          </svg>
          首页
        </button>
        <div class="w-8 h-8 rounded-lg bg-indigo-600 flex items-center justify-center text-white font-semibold">
          民
        </div>
        <div>
          <h1 class="text-sm font-semibold text-stone-900 leading-tight">民宿预约 Agent</h1>
          <p class="text-xs text-stone-500">你带来一句话，带走一个决断</p>
        </div>
      </div>
      <div class="flex items-center gap-2">
        <button
          type="button"
          class="text-xs text-stone-400 hover:text-indigo-600 transition-colors"
          @click="store.exportMarkdown()"
        >
          导出对话
        </button>
        <button
          type="button"
          class="text-xs text-stone-400 hover:text-stone-600 transition-colors"
          @click="store.reset()"
        >
          清空对话
        </button>
      </div>
    </header>

    <!-- 消息列表 -->
    <main ref="listEl" class="flex-1 overflow-y-auto px-4 py-4 space-y-4">
      <!-- 空态引导 -->
      <div v-if="store.messages.length === 0" class="h-full flex flex-col items-center justify-center text-center">
        <div class="w-14 h-14 rounded-2xl bg-indigo-100 flex items-center justify-center text-indigo-600 mb-4">
          <svg viewBox="0 0 24 24" fill="none" class="w-7 h-7" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M8.25 21v-4.875c0-.621.504-1.125 1.125-1.125h5.25c.621 0 1.125.504 1.125 1.125V21m0 0h4.5V3.545M12.75 21h7.5V10.75M2.25 21h1.5m18 0h-18M2.25 9l4.5-1.636M18.75 3l-1.5.545m0 6.205 3 1m1.5.5-1.5-.5M6.75 7.364V3h-3v18m3-13.636 10.5-3.819" />
          </svg>
        </div>
        <h2 class="text-base font-medium text-stone-800">想去哪儿住？</h2>
        <p class="mt-1 text-sm text-stone-500 max-w-xs">
          直接说你的需求，Agent 会一步步收敛，替你筛掉选择，给出一个能直接决策的推荐。
        </p>
        <div class="mt-4 flex flex-wrap gap-2 justify-center">
          <button
            v-for="q in ['莫干山，11月1日到3日，预算每晚800，情侣', '带孩子去莫干山亲子游，预算1000']"
            :key="q"
            type="button"
            class="px-3 py-1.5 rounded-full border border-stone-200 bg-white text-xs text-stone-600 hover:border-indigo-300 hover:text-indigo-600 transition-colors"
            @click="store.send(q)"
          >
            {{ q }}
          </button>
        </div>
      </div>

      <!-- 消息 -->
      <template v-else>
        <ChatMessage v-for="m in store.messages" :key="m.id" :message="m" />
      </template>
    </main>

    <!-- 输入框区域（语音 + 看图选房 + 联网搜索开关 + 文本输入） -->
    <div class="border-t border-stone-200 bg-white px-4 py-3">
      <div class="flex items-center gap-2 mb-2">
        <VoiceInput @recognized="fillInput" />
        <ImageSelect @recognized="fillInput" />
      </div>
      <ChatInput
        v-model="inputText"
        :loading="store.loading || searching"
        :web-search="webSearchOn"
        @send="handleSend"
        @toggle-web-search="webSearchOn = !webSearchOn"
      />
    </div>
  </div>
</template>
