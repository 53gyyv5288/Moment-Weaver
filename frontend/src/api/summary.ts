/**
 * 采访摘要 API。
 *
 * 后端 Spring Boot 在 InterviewService.close() 后会自动异步触发一次摘要；
 * 这里再暴露一个「手动重新生成」按钮：POST /api/v1/interview/sessions/{id}/summarize
 */
import { backend } from './client'
import type { ApiResult, InterviewSessionVO } from '@/types/api'

export function summarizeSession(sessionId: string) {
  return backend.post<ApiResult<InterviewSessionVO>>(
    `/v1/interview/sessions/${sessionId}/summarize`,
    {},
  )
}