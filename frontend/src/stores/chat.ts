import { defineStore } from 'pinia'
import { ref } from 'vue'
import { streamChat } from '@/api/chat'
import type { ChatMessage, RoomCard, StepEvent } from '@/types/chat'

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
  /**
   * 最近一次 DECIDING 下发的房源卡片缓存。
   *
   * 背景（2026-11-17 修复）：下单那轮 Agent 只调 create_order，不重新走 DECIDING，
   * 因此下单消息自身没有 cards，导致 OrderPayCard 拿不到房源名/封面图，
   * 订单落成「民宿订单」+ 裂图。这里在会话内缓存最近一次卡片，
   * 下单消息可复用，正确反查出真实民宿名与图片。
   */
  const lastCards = ref<RoomCard[]>([])

  /** 当前流式请求的 AbortController（清空对话/卸载时中止，防残留 chunk 写已清空的消息）。 */
  let activeAbort: AbortController | null = null

  /** 生成会话 ID（本地随机，页面存活期间不变）。 */
  function genSessionId(): string {
    return 'web-' + Math.random().toString(36).slice(2, 10)
  }

  /** 中止当前进行中的流式请求（幂等）。 */
  function abortActive(): void {
    if (activeAbort) {
      activeAbort.abort()
      activeAbort = null
    }
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
    // 修复：不再用非空断言！用户流式中点「清空对话」后 find 返回 undefined，
    // 直接解引用会抛 TypeError（且 onError 内二次抛）。改为判空，消息不在就静默丢弃流片段。
    const agentMsg = () => messages.value.find((m) => m.id === agentId)

    // 3. 清空上一轮思考链，进入加载态；建立本轮取消控制器
    activeSteps.value = []
    loading.value = true
    abortActive()
    const controller = new AbortController()
    activeAbort = controller

    try {
      await streamChat(trimmed, sessionId.value, {
        onStep: (event) => {
          const msg = agentMsg()
          if (!msg) return // 消息已被清空，丢弃该事件
          // 思考链步骤去重（同 type 可能重复，取最新）
          const existing = activeSteps.value.findIndex((s) => s.type === event.type)
          if (existing >= 0) {
            activeSteps.value[existing] = event
          } else {
            activeSteps.value.push(event)
          }
          // 同步到 Agent 消息的 steps
          msg.steps = [...activeSteps.value]

          // DECIDING 步骤携带结构化房源 payload → 存到消息上，供卡片式多方案对比渲染
          if (event.type === 'DECIDING' && event.payload) {
            const p = event.payload as { rooms?: RoomCard[]; checkIn?: string; checkOut?: string; guests?: number }
            if (Array.isArray(p.rooms)) {
              msg.cards = p.rooms
              lastCards.value = p.rooms
            }
            if (p.checkIn && p.checkOut) {
              msg.booking = { checkIn: p.checkIn, checkOut: p.checkOut, guests: p.guests ?? 2 }
            }
          }
          // 结构化订单结果：下单/支付工具成功时下发 kind=order_result
          const op = event.payload as { kind?: string }
          if (op.kind === 'order_result') {
            msg.order = event.payload as import('@/types/chat').OrderResult
          }
        },
        onText: (chunk) => {
          const msg = agentMsg()
          if (!msg) return
          msg.content += chunk
        },
        onDone: () => {
          const msg = agentMsg()
          if (msg) msg.streaming = false
          loading.value = false
        },
        onError: (err) => {
          const msg = agentMsg()
          if (!msg) return
          if (!msg.content) {
            msg.content = `（出错了：${err}）`
          }
          msg.streaming = false
          loading.value = false
        },
      }, controller.signal)
    } finally {
      // 兜底收尾：无论正常结束/异常/中止都清理加载态与本轮控制器
      const msg = agentMsg()
      if (msg) msg.streaming = false
      loading.value = false
      if (activeAbort === controller) activeAbort = null
    }
  }

  /** 清空对话（保留 sessionId 以便继续同会话，或可选重置）。 */
  function reset(): void {
    abortActive() // 先中止在途 SSE，杜绝后续 chunk 写已清空的消息
    messages.value = []
    activeSteps.value = []
    loading.value = false
    lastCards.value = []
  }

  /**
   * 把当前对话整理成 Markdown 文本（含思考链步骤），供导出下载。
   * 格式：标题 + 时间 + 逐条消息（用户/Agent），Agent 消息附思考链步骤。
   */
  function toMarkdown(): string {
    const lines: string[] = []
    lines.push('# 栖迟 Agent 对话记录')
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

  return { messages, activeSteps, loading, sessionId, lastCards, send, reset, abortActive, toMarkdown, exportMarkdown }
})
