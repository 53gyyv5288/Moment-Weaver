/**
 * 成稿 / Draft API。
 * 后端路由：/api/v1/projects/{pid}/drafts、/api/v1/drafts/{did}
 */
import { backend } from './client'
import type {
  ApiResult,
  NarrativeDraftVO,
  CreateDraftReq,
  UpdateSectionReq,
  PublishDraftReq,
  RewriteStyle,
} from '@/types/api'

export type {
  NarrativeDraftVO,
  SectionVO,
  FactSnapshotVO,
  CreateDraftReq,
  UpdateSectionReq,
  PublishDraftReq,
  RewriteStyle,
  SectionProvenance,
} from '@/types/api'

/** 列表查询参数 */
export interface ListDraftQuery {
  scope?: 'person' | 'family'
  status?: 'pending' | 'draft' | 'published' | 'archived'
  page?: number
  size?: number
}

/** 创建空成稿（不调 AI） */
export function createDraft(projectId: string | number, data: CreateDraftReq) {
  return backend.post<ApiResult<NarrativeDraftVO>>(
    `/v1/projects/${projectId}/drafts`,
    data,
  )
}

/** 列成稿 */
export function listDrafts(projectId: string | number, q: ListDraftQuery = {}) {
  const params = new URLSearchParams()
  if (q.scope) params.set('scope', q.scope)
  if (q.status) params.set('status', q.status)
  if (q.page) params.set('page', String(q.page))
  if (q.size) params.set('size', String(q.size))
  const qs = params.toString()
  return backend.get<ApiResult<NarrativeDraftVO[]>>(
    `/v1/projects/${projectId}/drafts${qs ? `?${qs}` : ''}`,
  )
}

/** 详情 */
export function getDraft(draftId: string) {
  return backend.get<ApiResult<NarrativeDraftVO>>(`/v1/drafts/${draftId}`)
}

/** AI 整篇生成（用 factsSnapshot 喂 AI）
 *
 * 家族成稿 family-template-v1 (3 subjects) 实测 4~6 分钟；
 * 整链：Vite proxy → Spring(1200s .block) → AI service(600s httpx) → MiniMax-M3
 * 全局 client.ts 里 backend.timeout=15000 远远不够，会让 axios 在 15s 抛 timeout，
 * 前端 spinner 停、用户以为失败，但后端实际还在跑。覆写 timeout 到 20 分钟跟 Spring 对齐。
 */
export function generateDraft(draftId: string) {
  return backend.post<ApiResult<NarrativeDraftVO>>(
    `/v1/drafts/${draftId}/generate`,
    {},
    { timeout: 1_200_000 },
  )
}

/** 更新章节（人工编辑 content；或 AI 重写 rewriteStyle）
 *
 * 人工编辑是快操作（< 1s），不需要 10 分钟。但 AI 重写单章节 5 分钟
 * （llm_timeout_s=300s + max_tokens=4096）。没法在这里精确分流
 * （axios timeout 必须在调用时设），统一给 5 分钟兜底。
 */
export function updateSection(
  draftId: string,
  sectionId: string,
  data: UpdateSectionReq,
  ifMatchVersion?: number,
) {
  const headers: Record<string, string> = {}
  if (ifMatchVersion !== undefined) headers['If-Match'] = String(ifMatchVersion)
  return backend.patch<ApiResult<NarrativeDraftVO>>(
    `/v1/drafts/${draftId}/sections/${sectionId}`,
    data,
    { headers, timeout: 300_000 },
  )
}

/** 发布 */
export function publishDraft(draftId: string, data: PublishDraftReq = {}) {
  return backend.post<ApiResult<NarrativeDraftVO>>(
    `/v1/drafts/${draftId}/publish`,
    data,
  )
}

/** 重写风格标签 */
export const REWRITE_STYLE_LABELS: Record<RewriteStyle, string> = {
  warmer: '更温暖',
  concise: '更简洁',
  vivid: '更生动',
  formal: '更正式',
}
