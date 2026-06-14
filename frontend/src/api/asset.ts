/**
 * 素材 / Asset API。
 * mock 模式走 multipart；real 模式拿到 STS 后直传再回调 create。
 */
import { backend } from './client'
import type { ApiResult } from '@/types/api'

export interface AssetVO {
  id: string
  projectId: string
  subjectId?: string
  interviewId?: string
  kind: 'image' | 'audio' | 'video' | string
  storage: 'oss' | 'local' | string
  /** mock 模式 = /api/v1/assets/{id}/file；real 模式 = 签名 URL */
  url: string
  originalName?: string
  mimeType?: string
  sizeBytes?: number
  width?: number
  height?: number
  durationMs?: number
  caption?: string
  takenAt?: string
  scanStatus?: string
  createdAt?: string
}

export interface CreateAssetReq {
  kind: string
  storage: 'oss' | 'local'
  ossKey: string
  ossBucket: string
  ossRegion: string
  originalName?: string
  mimeType?: string
  sizeBytes?: number
  width?: number
  height?: number
  caption?: string
  subjectId?: number
  interviewId?: string
  takenAt?: string
}

// ========== real 模式：OSS 直传后回调 ==========
export function createAsset(projectId: string | number, data: CreateAssetReq) {
  return backend.post<ApiResult<AssetVO>>(`/v1/projects/${projectId}/assets`, data)
}

// ========== 通用列表 / 详情 ==========
export function listProjectAssets(
  projectId: string | number,
  params: { subjectId?: number; kind?: string; from?: string; to?: string } = {},
) {
  const q = new URLSearchParams()
  if (params.subjectId) q.set('subjectId', String(params.subjectId))
  if (params.kind) q.set('kind', params.kind)
  if (params.from) q.set('from', params.from)
  if (params.to) q.set('to', params.to)
  const qs = q.toString()
  return backend.get<ApiResult<AssetVO[]>>(
    `/v1/projects/${projectId}/assets${qs ? `?${qs}` : ''}`,
  )
}

export function listSubjectAssets(subjectId: string | number) {
  return backend.get<ApiResult<AssetVO[]>>(`/v1/subjects/${subjectId}/assets`)
}

export function getAsset(id: string | number) {
  return backend.get<ApiResult<AssetVO>>(`/v1/assets/${id}`)
}

export function deleteAsset(id: string | number) {
  return backend.delete<ApiResult<null>>(`/v1/assets/${id}`)
}

// ========== mock 模式：multipart 上传 ==========
export function uploadMock(
  projectId: string | number,
  file: File,
  opts: { subjectId?: number; interviewId?: string; caption?: string; onProgress?: (e: ProgressEvent) => void } = {},
): Promise<{ data: ApiResult<AssetVO> }> {
  const fd = new FormData()
  fd.append('file', file)
  if (opts.subjectId) fd.append('subjectId', String(opts.subjectId))
  if (opts.interviewId) fd.append('interviewId', opts.interviewId)
  if (opts.caption) fd.append('caption', opts.caption)
  return backend.post<ApiResult<AssetVO>>(`/v1/projects/${projectId}/assets`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: opts.onProgress,
  })
}