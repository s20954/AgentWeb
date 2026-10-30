/**
 * 工具层客户端 —— 消费后端 /api/tools/{name}/run（报价等）。
 */

export interface QuoteData {
  roomId: string
  title: string
  nights: number
  guests: number
  fee: { roomFee: number; cleaning: number; service: number }
  total: number
}

export interface QuoteError {
  errorType?: string
  retryable?: boolean
  message?: string
}

export interface QuoteResponse {
  toolName?: string
  success: boolean
  data?: QuoteData | QuoteError
  note?: string
}

/**
 * 对某套房源计算含全部费用的最终报价（后端 quote_price，只读）。
 * @param args 房源与入住信息（roomId + 入住/离店日期 + 可选人数）
 * @param userId 会话用户 ID（后端从 X-User-Id 注入；quote_price 本身不依赖具体用户）
 */
export async function quotePrice(
  args: { roomId: string; checkIn: string; checkOut: string; guests?: number },
  userId = 'web-demo',
): Promise<QuoteResponse> {
  const res = await fetch('/api/tools/quote_price/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-User-Id': userId },
    body: JSON.stringify(args),
  })
  if (!res.ok) {
    return { success: false, data: { errorType: 'HTTP', message: `请求失败：HTTP ${res.status}` } }
  }
  return (await res.json()) as QuoteResponse
}