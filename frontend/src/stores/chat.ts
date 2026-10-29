import { defineStore } from 'pinia'
import { ref } from 'vue'
import { streamChat } from '@/api/chat'
import type { ChatMessage, StepEvent } from '@/types/chat'

let idCounter = 0
function nextId(): string {
  return `msg-${Date.now()}-${idCounter++}`
}

/**
 * 对话状态 —— 消息列表 + 思考链 + SSE 流式驱动。
 */
export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  /** 当前这一轮正在渲染的思考链步骤。 */
  const activeSteps = ref<StepEvent[]>([])
  /** 是否正在等待 Agent 回复。 */
  const loading = ref(false)
  /** 会话 ID（多轮复用，保持后端状态累积）。 */
  const sessionId = ref<string>(genSessionId())

  /** 生成会话 ID（本地随机，页面存活期间不变）。 */
  function genSessionId(): string {
    return 'web-' + Math.random().toString(36).slice(2, 10)
  }

  /** 发送一条用户消息并流式接收 Agent 回复。 */
  async function send(text: string): Promise<void> {
    const trimmed = text.trim()
    if (!trimmed || loading.value) return

    // 1. 推用户消息
    messages.value.push({ id: nextId(), role: 'user', content: trimmed })

    // 2. 建一条空 Agent 消息（后续流式填充）
    const agentId = nextId()
    messages.value.push({
      id: agentId,
      role: 'agent',
      content: '',
      streaming: true,
      steps: [],
    })
    // 关键：通过 messages.value 里的元素（响应式 proxy）更新，而不是改局部对象引用，
    // 否则 Vue 3 深层响应式检测不到局部变量的属性变化，界面不更新。
    const agentMsg = () => messages.value.find((m) => m.id === agentId)!

    // 3. 清空上一轮思考链，进入加载态
    activeSteps.value = []
    loading.value = true

    await streamChat(trimmed, sessionId.value, {
      onStep: (event) => {
        // 思考链步骤去重（同 type 可能重复，取最新）
        const existing = activeSteps.value.findIndex((s) => s.type === event.type)
        if (existing >= 0) {
          activeSteps.value[existing] = event
        } else {
          activeSteps.value.push(event)
        }
        // 同步到 Agent 消息的 steps
        agentMsg().steps = [...activeSteps.value]
      },
      onText: (chunk) => {
        agentMsg().content += chunk
      },
      onDone: () => {
        agentMsg().streaming = false
        loading.value = false
      },
      onError: (err) => {
        if (!agentMsg().content) {
          agentMsg().content = `（出错了：${err}）`
        }
        agentMsg().streaming = false
        loading.value = false
      },
    })

    // 兜底：异常未触发 onDone/onError 时收尾
    agentMsg().streaming = false
    loading.value = false
  }

  /** 清空对话（保留 sessionId 以便继续同会话，或可选重置）。 */
  function reset(): void {
    messages.value = []
    activeSteps.value = []
    loading.value = false
  }

  /**
   * 把当前对话整理成 Markdown 文本（含思考链步骤），供导出下载。
   * 格式：标题 + 时间 + 逐条消息（用户/Agent），Agent 消息附思考链步骤。
   */
  function toMarkdown(): string {
    const lines: string[] = []
    lines.push('# 民宿预约 Agent 对话记录')
    lines.push('')
    lines.push(`> 导出时间：${new Date().toLocaleString('zh-CN')}`)
    lines.push('')
    if (messages.value.length === 0) {
      lines.push('（暂无对话内容）')
      return lines.join('\n')
    }
    for (const m of messages.value) {
      if (m.role === 'user') {
        lines.push(`## 👤 我`)
        lines.push('')
        lines.push(m.content)
      } else {
        lines.push(`## 🤖 Agent`)
        lines.push('')
        if (m.steps && m.steps.length > 0) {
          lines.push('<details>')
          lines.push('<summary>思考过程</summary>')
          lines.push('')
          for (const s of m.steps) {
            lines.push(`- ${s.message}`)
          }
          lines.push('')
          lines.push('</details>')
          lines.push('')
        }
        lines.push(m.content)
      }
      lines.push('')
      lines.push('---')
      lines.push('')
    }
    return lines.join('\n')
  }

  /** 导出对话为本地 .md 文件（触发浏览器下载）。 */
  function exportMarkdown(): void {
    const md = toMarkdown()
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `民宿对话-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.md`
    a.click()
    URL.revokeObjectURL(url)
  }

  return { messages, activeSteps, loading, sessionId, send, reset, toMarkdown, exportMarkdown }
})
