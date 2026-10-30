/**
 * 对话相关类型 —— 对齐后端 /api/chat/stream 的 SSE 事件协议。
 */

/** 思考链步骤类型（对齐后端 StepEventType 的七步收敛）。 */
export type StepType =
  | 'EXTRACTING'
  | 'GAP_FOUND'
  | 'ASKING'
  | 'LOADING_KNOWLEDGE'
  | 'FILTERING'
  | 'PACKAGING'
  | 'DECIDING'

/** 思考链事件（SSE event=step 的 data 负载）。 */
export interface StepEvent {
  type: StepType
  message: string
  payload?: unknown
}

/** 结构化房源卡片（对齐后端 search_rooms 返回的 Room，随 DECIDING 步骤 payload 下发）。 */
export interface RoomCard {
  roomId: string
  area: string
  title: string
  positioning: string
  basePrice: number
  style: string
  fitReason: string
  drawback: string
  /** 封面图 URL（Unsplash 占位，前端渲染真实图，失败时回退风格渐变）。 */
  cover?: string
}

/** 消息角色。 */
export type MessageRole = 'user' | 'agent'

/** 一条对话消息。 */
export interface ChatMessage {
  id: string
  role: MessageRole
  content: string
  /** 是否仍在流式输出中（打字机效果）。 */
  streaming?: boolean
  /** 该消息对应的思考链步骤（Agent 消息才有）。 */
  steps?: StepEvent[]
  /** 结构化房源卡片（Agent 筛出候选时随 DECIDING 步骤下发，用于渲染多方案对比）。 */
  cards?: RoomCard[]
  /** 预订上下文（同一 DECIDING 步骤随卡片下发，供「帮我报价」按钮调用 quote_price）。 */
  booking?: BookingContext
}

/** 预订上下文：报价/下单所需的入住信息。 */
export interface BookingContext {
  checkIn: string
  checkOut: string
  guests: number
}

/** 当前会话一轮的思考链进度。 */
export interface ThinkingProgress {
  /** 已完成的步骤（按序）。 */
  steps: StepEvent[]
  /** 当前正在执行的步骤。 */
  current?: StepEvent
}
