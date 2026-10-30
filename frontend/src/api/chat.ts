import type { StepEvent } from '@/types/chat'

/**
 * SSE 流式对话客户端 —— 消费后端 /api/chat/stream。
 *
 * 后端事件协议（event=step/text/done/error，data 为 JSON 或字符串）。
 * 用 fetch + ReadableStream 手动解析，比 EventSource 更灵活（支持 POST + 自定义头）。
 */
export interface StreamHandlers {
  /** 思考链步骤事件。 */
  onStep?: (event: StepEvent) => void
  /** 文本片段（可多次，逐段渲染打字机效果）。 */
  onText?: (text: string) => void
  /** 结束。 */
  onDone?: () => void
  /** 出错。 */
  onError?: (message: string) => void
}

/**
 * 发送一次流式对话。
 *
 * @param message 用户输入
 * @param sessionId 会话 ID（多轮复用，保持状态累积）
 * @param handlers 事件回调
 */
export async function streamChat(
  message: string,
  sessionId: string,
  handlers: StreamHandlers,
): Promise<void> {
  // fetch 本身必须包进 try：后端重启/代理断开时 fetch 直接 reject，
  // 不捕获会绕过 onError 让调用方陷入永久 loading（界面卡死、控制台报 net::ERR_ABORTED）
  let res: Response
  try {
    res = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': sessionId,
      },
      body: JSON.stringify({ message, sessionId }),
    })
  } catch (e) {
    handlers.onError?.(e instanceof Error ? e.message : String(e))
    return
  }

  if (!res.ok || !res.body) {
    handlers.onError?.(`请求失败：HTTP ${res.status}`)
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // SSE 事件以空行分隔（一个事件可能跨 chunk，按 \n\n 切分，余量留在 buffer）
      let sepIndex: number
      while ((sepIndex = buffer.indexOf('\n\n')) !== -1) {
        const rawEvent = buffer.slice(0, sepIndex)
        buffer = buffer.slice(sepIndex + 2)
        handleSseEvent(rawEvent, handlers)
      }
    }
  } catch (e) {
    handlers.onError?.(e instanceof Error ? e.message : String(e))
  }
}

/** 解析单个 SSE 事件块（`event: xxx\ndata: {...}`）。 */
function handleSseEvent(raw: string, handlers: StreamHandlers): void {
  let eventType = ''
  let data = ''

  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      eventType = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      data = line.slice(5).trim()
    }
  }

  if (!eventType) return

  switch (eventType) {
    case 'step':
      try {
        handlers.onStep?.(JSON.parse(data) as StepEvent)
      } catch {
        // 忽略无法解析的 step
      }
      break
    case 'text':
      // text 的 data 是 JSON 字符串（后端做了 JSON 序列化），需二次解析
      handlers.onText?.(parseTextPayload(data))
      break
    case 'done':
      handlers.onDone?.()
      break
    case 'error':
      handlers.onError?.(parseTextPayload(data))
      break
  }
}

/** 解析 text/error 的 data：可能是 JSON 字符串，也可能已带引号。 */
function parseTextPayload(data: string): string {
  try {
    const parsed = JSON.parse(data)
    return typeof parsed === 'string' ? parsed : String(parsed)
  } catch {
    return data
  }
}
