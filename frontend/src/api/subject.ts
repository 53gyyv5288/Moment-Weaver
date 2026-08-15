/**
 * 人物 / Subject API。
 * 后端路由：/api/v1/projects/{projectId}/subjects
 */
import { backend } from './client'
import type {
  ApiResult,
  SubjectVO,
  CreateSubjectReq,
  UpdateSubjectReq,
  EligibleFamilyMemberVO,
} from '@/types/api'

export type { SubjectVO, CreateSubjectReq, UpdateSubjectReq, EligibleFamilyMemberVO }

export function listSubjects(projectId: string | number) {
  return backend.get<ApiResult<SubjectVO[]>>(`/v1/projects/${projectId}/subjects`)
}

/**
 * M11 Phase 2：列出项目下"可选被采访者"（从家族成员里筛）。
 * 个人项目返回空列表。
 */
export function listEligibleSubjects(projectId: string | number) {
  return backend.get<ApiResult<EligibleFamilyMemberVO[]>>(
    `/v1/projects/${projectId}/subjects/eligible`,
  )
}

export function getSubject(projectId: string | number, id: string | number) {
  return backend.get<ApiResult<SubjectVO>>(`/v1/projects/${projectId}/subjects/${id}`)
}

export function createSubject(projectId: string | number, data: CreateSubjectReq) {
  return backend.post<ApiResult<SubjectVO>>(`/v1/projects/${projectId}/subjects`, data)
}

export function updateSubject(
  projectId: string | number,
  id: string | number,
  data: UpdateSubjectReq,
) {
  return backend.put<ApiResult<SubjectVO>>(`/v1/projects/${projectId}/subjects/${id}`, data)
}

export function deleteSubject(projectId: string | number, id: string | number) {
  return backend.delete<ApiResult<null>>(`/v1/projects/${projectId}/subjects/${id}`)
}
