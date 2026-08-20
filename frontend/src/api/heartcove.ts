/**
 * 心声信箱（数字先辈 / AI 复刻）API 客户端。
 *
 * 入口命名克制：与"采访"完全解耦；不导出任何用户对话内容（仅本人可见）。
 */
import { backend } from './client'
import { useAuthStore } from '@/stores/auth'
import type { ApiResult } from '@/types/api'

// ===== 授权相关 =====

export interface HeartcoveStatus {
  enabled: 0 | 1
  interviewCount: number
  turnsToGo: number
  enabledAt?: string
  consentVersion?: string
  grantorId?: string
}

export interface HeartcoveConsentText {
  version: string
  title: string
  body: string
}

export interface HeartcoveEnableRequest {
  consentVersion: string
  agreed: true
  note?: string
}

// ===== 入口聚合：已开启心信箱的人物 =====

export interface EnabledHeartcoveSubjectVO {
  subjectId: string
  subjectDisplayName: string
  subjectRelation?: string | null
  projectId: string
  projectName: string
  projectType?: number | null
  familyId?: string | null
  heartcoveEnabledAt?: string | null
  consentVersion?: string | null
}

// ===== 会话 + 消息 =====

export interface HeartcoveMessageVO {
  id: string
  role: 'user' | 'ai'
  content: string
  sourceMessageIds?: string | null
  unknownType?: string | null
  safetyFlag?: string | null
  // M14+: LLM 在回复末尾输出的 <<EVIDENCE>> 段(已剥离, 不在 content 里)
  // 本期不持久化, 重新加载历史消息时为空
  evidence?: string[]
  // M14+: 推理模型的思考过程,挂在这条消息上;前端本地字段,不持久化,
  // 刷新页面或重新打开会话后丢失——用户要的就是"暂时保存"语义
  thinking?: string
  createdAt: string
}

export interface HeartcoveSessionVO {
  id: string
  subjectId: string
  subjectDisplayName?: string
  status: 'active' | 'closed'
  messageCount: number
  lastMessageAt?: string | null
  startedAt: string
  closedAt?: string | null
  messages: HeartcoveMessageVO[]
}

// ===== HTTP API =====

export async function getHeartcoveStatus(subjectId: string | number): Promise<HeartcoveStatus> {
  const r = await backend.get<ApiResult<HeartcoveStatus>>(
    `/v1/heartcove/subjects/${subjectId}/status`,
  )
  return r.data.data as HeartcoveStatus
}

export async function getConsentText(): Promise<HeartcoveConsentText> {
  const r = await backend.get<ApiResult<HeartcoveConsentText>>(
    `/v1/heartcove/subjects/0/consent-text`,
  )
  return r.data.data as HeartcoveConsentText
}

export async function enableHeartcove(subjectId: string | number, req: HeartcoveEnableRequest): Promise<HeartcoveStatus> {
  const r = await backend.post<ApiResult<HeartcoveStatus>>(
    `/v1/heartcove/subjects/${subjectId}/enable`,
    req,
  )
  return r.data.data as HeartcoveStatus
}

export async function disableHeartcove(subjectId: string | number): Promise<HeartcoveStatus> {
  const r = await backend.post<ApiResult<HeartcoveStatus>>(
    `/v1/heartcove/subjects/${subjectId}/disable`,
  )
  return r.data.data as HeartcoveStatus
}

export async function openHeartcoveSession(subjectId: string | number): Promise<HeartcoveSessionVO> {
  const r = await backend.post<ApiResult<HeartcoveSessionVO>>(
    `/v1/heartcove/sessions/open?subjectId=${subjectId}`,
  )
  return r.data.data as HeartcoveSessionVO
}

export async function getHeartcoveSession(sessionId: string | number): Promise<HeartcoveSessionVO> {
  const r = await backend.get<ApiResult<HeartcoveSessionVO>>(
    `/v1/heartcove/sessions/${sessionId}`,
  )
  return r.data.data as HeartcoveSessionVO
}

export async function closeHeartcoveSession(sessionId: string | number): Promise<HeartcoveSessionVO> {
  const r = await backend.post<ApiResult<HeartcoveSessionVO>>(
    `/v1/heartcove/sessions/${sessionId}/close`,
  )
  return r.data.data as HeartcoveSessionVO
}

export async function listMyEnabledHeartcoveSubjects(): Promise<EnabledHeartcoveSubjectVO[]> {
  const r = await backend.get<ApiResult<EnabledHeartcoveSubjectVO[]>>(
    `/v1/heartcove/my-enabled-subjects`,
  )
  return (r.data.data as EnabledHeartcoveSubjectVO[]) ?? []
}

// ===== 流式对话（SSE） =====

export interface StreamCallbacks {
  onToken: (token: string) => void
  // M14+: 推理模型的思考链,前端折叠展示;非推理模型不会触发
  onThinking?: (token: string) => void
  // ⛔ 溯源修复:sourceQuoteIds 是 MongoDB interview_message._id 字符串列表,不再是数组下标
  onMeta?: (meta: { unknownType?: string; sourceQuoteIds?: string[] }) => void
  onError?: (msg: string) => void
  onDone?: () => void
}

/**
 * 用 fetch + ReadableStream 解析 /heartcove/sessions/{id}/chat/stream 的 SSE 流。
 * AI 服务的 SSE 事件：
 *   - event: token    data: <text>
 *   - event: meta     data: {unknown_type, source_quote_ids}
 *   - event: error    data: <msg>
 *   - event: done     data:
 */
export async function streamHeartcoveChat(
  sessionId: string | number,
  userMsg: string,
  cb: StreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  // 注意：用 fetch 而不是 axios，因为 SSE 流式响应 axios 不友好。
  // 从 Pinia store 拿 token，避免 key 字面量跟 stores/auth.ts 不一致（早期写法直接读
  // localStorage.getItem('accessToken')，但真正的 key 是 'mw_access_token'，永远是 null）。
  const auth = useAuthStore()
  const resp = await fetch(`/api/v1/heartcove/sessions/${sessionId}/chat/stream?user_msg=${encodeURIComponent(userMsg)}`, {
    method: 'POST',
    headers: {
      ...(auth.accessToken ? { Authorization: `Bearer ${auth.accessToken}` } : {}),
      Accept: 'text/event-stream',
    },
    signal,
  })
  if (!resp.ok || !resp.body) {
    cb.onError?.(`HTTP ${resp.status}`)
    return
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let doneFired = false

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // 按 \n\n 切分帧
      let idx: number
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)

        // 与 interview.ts 一致的 SSE 解析：每个 frame 内独立 event / data，
        // 不依赖 startsWith 后是否带空格，对 data:[DONE] 兼容 OpenAI 风格。
        let event = 'message'
        let data = ''
        for (const line of frame.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) data += (data ? '\n' : '') + line.slice(5).trim()
        }
        if (event === 'done' || data === '[DONE]') {
          doneFired = true
          return
        }
        if (!data) continue

        if (event === 'token') {
          cb.onToken(data)
        } else if (event === 'thinking') {
          cb.onThinking?.(data)
        } else if (event === 'meta') {
          try {
            const m = JSON.parse(data)
            cb.onMeta?.(m)
          } catch { /* ignore */ }
        } else if (event === 'error') {
          cb.onError?.(data)
        }
      }
    }
  } finally {
    try {
      reader.releaseLock()
    } catch { /* ignore */ }
  }
  if (!doneFired) cb.onDone?.()
}