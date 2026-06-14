/**
 * 授权 / Authorization API。
 * 后端路由：
 *   Owner 端（JWT）：/api/v1/projects/{projectId}/authorizations
 *   Owner 端：       /api/v1/authorizations/{id} (DELETE 撤销)
 *   Owner 端：       /api/v1/subjects/{subjectId}/authorizations
 *   公开端（无 JWT）：/api/v1/public/authz/{token}
 */
import { backend } from './client'
import type { ApiResult, AuthorizationVO, CreateAuthorizationReq } from '@/types/api'

export type { AuthorizationVO, CreateAuthorizationReq }

export function listAuthorizationsByProject(projectId: string | number) {
  return backend.get<ApiResult<AuthorizationVO[]>>(`/v1/projects/${projectId}/authorizations`)
}

export function listAuthorizationsBySubject(subjectId: string | number) {
  return backend.get<ApiResult<AuthorizationVO[]>>(`/v1/subjects/${subjectId}/authorizations`)
}

export function createAuthorization(
  projectId: string | number,
  data: CreateAuthorizationReq,
) {
  return backend.post<ApiResult<AuthorizationVO>>(
    `/v1/projects/${projectId}/authorizations`,
    data,
  )
}

export function revokeAuthorization(id: string | number) {
  return backend.delete<ApiResult<null>>(`/v1/authorizations/${id}`)
}

// ====== 公开端（无 JWT） ======

/** 单独 axios 实例走 no-auth，但这里简单复用 backend 即可，后端这个端点已 permitAll */
export function viewPublicAuthorization(token: string) {
  return backend.get<ApiResult<AuthorizationVO>>(`/v1/public/authz/${token}`)
}

export function grantPublicAuthorization(token: string) {
  return backend.post<ApiResult<AuthorizationVO>>(`/v1/public/authz/${token}/grant`, {})
}

export function denyPublicAuthorization(token: string) {
  return backend.post<ApiResult<AuthorizationVO>>(`/v1/public/authz/${token}/deny`, {})
}
