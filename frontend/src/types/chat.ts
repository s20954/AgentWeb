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
}

/** 当前会话一轮的思考链进度。 */
export interface ThinkingProgress {
  /** 已完成的步骤（按序）。 */
  steps: StepEvent[]
  /** 当前正在执行的步骤。 */
  current?: StepEvent
}
