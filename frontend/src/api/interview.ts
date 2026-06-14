/**
 * 采访 / Interview API。
 * 流式发送走原生 fetch + ReadableStream 解析 SSE 协议，
 * 避免引入 @microsoft/fetch-event-source 的浏览器打包坑。
 */
import { backend } from './client'
import { useAuthStore } from '@/stores/auth'
import type {
  ApiResult,
  InterviewSessionVO,
  InterviewMessageVO,
} from '@/types/api'

export type { InterviewSessionVO, InterviewMessageVO }

// ====== 会话管理（普通 JSON） ======

export interface StartInterviewReq {
  projectId: string | number
  subjectId: string | number
  authorizationId?: string | number
}

export function startInterview(data: StartInterviewReq) {
  return backend.post<ApiResult<InterviewSessionVO>>('/v1/interview/sessions', data)
}

export function listInterviewSessions(projectId: string | number) {
  return backend.get<ApiResult<InterviewSessionVO[]>>(
    `/v1/interview/sessions?projectId=${projectId}`,
  )
}

export function getInterviewSession(id: string) {
  return backend.get<ApiResult<InterviewSessionVO>>(`/v1/interview/sessions/${id}`)
}

export function closeInterviewSession(id: string) {
  return backend.post<ApiResult<InterviewSessionVO>>(
    `/v1/interview/sessions/${id}/close`,
    {},
  )
}

// ====== SSE 流式发送 ======

export interface StreamHandlers {
  onStart?: () => void
  onToken?: (token: string) => void
  onError?: (message: string) => void
  onDone?: () => void
}

/**
 * 流式发送用户消息。后端 Spring Boot SseEmitter 输出：
 *   event: start | token | error | done
 *   data: <json>
 */
export async function streamInterviewMessage(
  sessionId: string,
  content: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const auth = useAuthStore()
  const resp = await fetch(`/api/v1/interview/sessions/${sessionId}/message`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(auth.accessToken ? { Authorization: `Bearer ${auth.accessToken}` } : {}),
    },
    body: JSON.stringify({ content }),
    signal,
  })

  if (!resp.ok || !resp.body) {
    const text = await resp.text().catch(() => '')
    const msg = `HTTP ${resp.status}: ${text || resp.statusText}`
    handlers.onError?.(msg)
    throw new Error(msg)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // 按 \n\n 切分完整事件
      let idx: number
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (!raw || raw.startsWith(':')) continue // 心跳 / 注释
        let event = 'message'
        let data = ''
        for (const line of raw.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) data += line.slice(5).trim()
        }
        switch (event) {
          case 'start':
            handlers.onStart?.()
            break
          case 'token':
            // Spring Boot SseEmitter 的 data 是字符串内容，直接吐给 onToken
            handlers.onToken?.(data)
            break
          case 'error':
            handlers.onError?.(data)
            break
          case 'done':
            handlers.onDone?.()
            return
        }
      }
    }
    handlers.onDone?.()
  } finally {
    try {
      reader.releaseLock()
    } catch {
      /* ignore */
    }
  }
}
