/**
 * 智能检索客户端 —— 消费后端 /api/search（先查知识库，再联网，最后整合）。
 */

export interface KbHit {
  category: string
  content: string
  score: number
}

export interface WebResult {
  title: string
  url: string
}

export interface SearchResponse {
  query: string
  /** 本地知识库命中结果。 */
  kbResults?: KbHit[]
  /** 联网搜索结果。 */
  webResults?: WebResult[]
  /** 整合后的最终回答。 */
  answer?: string
  /** 结果来源标记。 */
  sources?: string[]
  /** 主来源：kb / web / kb+web。 */
  primarySource?: 'kb' | 'web' | 'kb+web'
  webError?: string
  error?: string
  message?: string
}

/**
 * 发起一次智能检索（先知识库、后联网、再整合）。
 * @param query 问题/关键词
 */
export async function webSearch(query: string): Promise<SearchResponse> {
  const res = await fetch('/api/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query }),
  })
  if (!res.ok) {
    return { query, error: `HTTP ${res.status}`, message: '智能检索请求失败' }
  }
  return (await res.json()) as SearchResponse
}
