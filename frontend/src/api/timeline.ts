/**
 * 时间线 API。
 * GET /api/v1/projects/{pid}/timeline?subjectId=&type=&from=&to=&page=&size=
 */
import { backend } from './client'
import type { ApiResult, PageResult, TimelineItemVO } from '@/types/api'

export type TimelineType = 'interview_message' | 'asset_uploaded' | 'ai_summary'

export interface TimelineQuery {
  subjectId?: string | number
  type?: TimelineType
  from?: string
  to?: string
  page?: number
  size?: number
}

export function listTimeline(projectId: string | number, q: TimelineQuery = {}) {
  const params = new URLSearchParams()
  if (q.subjectId) params.set('subjectId', String(q.subjectId))
  if (q.type) params.set('type', q.type)
  if (q.from) params.set('from', q.from)
  if (q.to) params.set('to', q.to)
  if (q.page) params.set('page', String(q.page))
  if (q.size) params.set('size', String(q.size))
  const qs = params.toString()
  return backend.get<ApiResult<PageResult<TimelineItemVO>>>(
    `/v1/projects/${projectId}/timeline${qs ? `?${qs}` : ''}`,
  )
}