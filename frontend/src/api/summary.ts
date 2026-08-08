/**
 * 采访摘要 API。
 *
 * 后端 Spring Boot 在 InterviewService.close() 后会自动异步触发一次摘要；
 * 这里再暴露一个「手动重新生成」按钮：POST /api/v1/interview/sessions/{id}/summarize
 *
 * 整链：Vite proxy → Spring（read-timeout-ms=1200000 .block）→ AI service
 *   （llm_timeout_s=600 httpx）→ MiniMax-M3
 * 全局 client.ts 里 backend.timeout=15000 远远不够：采访稍长就 1~2 分钟，踩到 4~6 分钟的也常见
 * （和 draft.generateDraft 同链路，已在 draft.ts 修过同样的问题）。覆写 timeout 到 20 分钟跟 Spring 对齐。
 */
import { backend } from './client'
import type { ApiResult, InterviewSessionVO } from '@/types/api'

export function summarizeSession(sessionId: string) {
  return backend.post<ApiResult<InterviewSessionVO>>(
    `/v1/interview/sessions/${sessionId}/summarize`,
    {},
    { timeout: 1_200_000 },
  )
}