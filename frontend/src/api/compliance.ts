/**
 * 合规中心 API (M5-B)。
 * 后端路由：/api/v1/me/{exports,deletion-requests,audit-log}、/api/v1/recycle-bin
 */
import { backend } from './client'
import type { ApiResult, PageResult } from '@/types/api'

// ============== 数据导出 ==============

export interface ExportRequestVO {
  id: string
  scope: 'all' | 'project' | 'subject' | string
  scopeTargetId?: string
  status: 'pending' | 'ready' | 'failed' | 'expired' | string
  signedUrl?: string
  signedUrlExpiresAt?: string
  failReason?: string
  createdAt: string
  completedAt?: string
}

export interface CreateExportReq {
  scope: 'all' | 'project' | 'subject'
  scopeTargetId?: string
}

export function createExport(data: CreateExportReq) {
  return backend.post<ApiResult<ExportRequestVO>>('/v1/me/exports', data)
}
export function listExports(page = 0, size = 20) {
  return backend.get<ApiResult<PageResult<ExportRequestVO>>>(
    `/v1/me/exports?page=${page}&size=${size}`,
  )
}
export function getExport(eid: string | number) {
  return backend.get<ApiResult<ExportRequestVO>>(`/v1/me/exports/${eid}`)
}

// ============== 删除申请 ==============

export interface DeletionRequestVO {
  id: string
  scopeTargetType: 'project' | 'subject' | 'asset' | 'draft' | string
  scopeTargetId: string
  status: 'pending' | 'restored' | 'executed' | 'cancelled' | string
  effectiveAt: string
  graceExpiresAt: string
  daysUntilExpiry: number
  executedAt?: string
  createdAt: string
}

export interface CreateDeletionReq {
  scopeTargetType: 'project' | 'subject' | 'asset' | 'draft'
  scopeTargetId: string
}

export function createDeletion(data: CreateDeletionReq) {
  return backend.post<ApiResult<DeletionRequestVO>>('/v1/me/deletion-requests', data)
}
export function listDeletions(page = 0, size = 20) {
  return backend.get<ApiResult<PageResult<DeletionRequestVO>>>(
    `/v1/me/deletion-requests?page=${page}&size=${size}`,
  )
}
export function restoreDeletion(did: string | number) {
  return backend.post<ApiResult<DeletionRequestVO>>(
    `/v1/me/deletion-requests/${did}/restore`,
  )
}

// ============== 回收站 ==============

export interface RecycleBinItemVO {
  type: 'project' | 'subject' | 'asset' | 'draft' | string
  id: string
  title: string
  deletedAt: string
  daysUntilPermanentDelete: number
  deletionRequestId: string
}

export function listRecycleBin(type?: string) {
  const qs = type ? `?type=${type}` : ''
  return backend.get<ApiResult<RecycleBinItemVO[]>>(`/v1/recycle-bin${qs}`)
}
export function restoreFromRecycleBin(type: string, id: string) {
  return backend.post<ApiResult<{ restored: boolean; type: string; id: string }>>(
    `/v1/recycle-bin/${type}/${id}/restore`,
  )
}

// ============== 审计日志 ==============

export interface AuditLogVO {
  id: string
  action: string
  targetType?: string
  targetId?: string
  ip?: string
  ua?: string
  metadata?: string
  createdAt: string
}

export function listAuditLog(page = 0, size = 20) {
  return backend.get<ApiResult<PageResult<AuditLogVO>>>(
    `/v1/me/audit-log?page=${page}&size=${size}`,
  )
}

// 审计动作的展示标签
export const AUDIT_ACTION_LABELS: Record<string, string> = {
  login: '登录',
  logout: '登出',
  share_create: '创建分享',
  share_revoke: '撤销分享',
  share_access: '分享被访问',
  export_create: '申请导出',
  export_download: '下载导出',
  delete_request: '申请删除',
  delete_restore: '恢复删除',
  delete_execute: '物理删除',
  revoke: '撤销授权',
  consent_accept: '同意授权',
}
