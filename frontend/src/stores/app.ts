import { defineStore } from 'pinia'
import { ref } from 'vue'

interface HealthResponse {
  status: string
  service: string
  version: string
  timestamp: string
}

/**
 * 应用级状态（后续对话消息、思考链事件、房源候选都在此扩展）。
 * 当前只含后端健康状态，作为 Pinia 接入的冒烟验证。
 */
export const useAppStore = defineStore('app', () => {
  const backendHealth = ref<HealthResponse | null>(null)
  const healthError = ref<string | null>(null)

  async function checkHealth() {
    healthError.value = null
    try {
      const res = await fetch('/api/health')
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      backendHealth.value = (await res.json()) as HealthResponse
    } catch (e) {
      healthError.value = e instanceof Error ? e.message : String(e)
      backendHealth.value = null
    }
  }

  return { backendHealth, healthError, checkHealth }
})
