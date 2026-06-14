/**
 * Axios 客户端。
 * /api    → Spring Boot BFF（由 vite proxy 转发到 8080）
 * /ai-api → FastAPI（由 vite proxy 转发到 8000）
 *
 * M1 起：注入 JWT、统一 401 处理。
 */
import axios, { type AxiosInstance } from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { ResultCode } from '@/types/api'

export const backend: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

export const ai: AxiosInstance = axios.create({
  baseURL: '/ai-api',
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' },
})

// ============== 请求拦截：注入 JWT ==============
backend.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.accessToken) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

// ============== 响应拦截：401 清登录 + 提示 ==============
backend.interceptors.response.use(
  (r) => r,
  (e) => {
    const status = e?.response?.status
    const code = e?.response?.data?.code
    const msg = e?.response?.data?.message || e.message

    if (status === 401 || code === ResultCode.TOKEN_INVALID || code === ResultCode.TOKEN_EXPIRED) {
      const auth = useAuthStore()
      // 避免在登录页死循环
      if (auth.isLoggedIn) {
        ElMessage.warning('登录已过期，请重新登录')
        auth.clear()
        // 用 location 强制跳转，避免再调一次 router
        const redirect = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.href = `/login?redirect=${redirect}`
      }
    } else if (status >= 500) {
      ElMessage.error(msg || '服务器开了小差')
    } else if (status >= 400 && msg) {
      ElMessage.error(msg)
    }
    return Promise.reject(e)
  }
)

ai.interceptors.response.use(
  (r) => r,
  (e) => {
    console.error('[ai]', e?.response?.status, e?.config?.url, e?.message)
    ElMessage.error(e?.response?.data?.detail || e.message || 'AI 服务异常')
    return Promise.reject(e)
  }
)
