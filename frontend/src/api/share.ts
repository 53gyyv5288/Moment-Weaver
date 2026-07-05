/**
 * 分享链接 API (M5-A)。
 *
 * Owner 端：/api/v1/projects/{pid}/shares、/api/v1/shares/{sid}（带 JWT）
 * 公开端：/api/v1/public/shares/**（不带 JWT，走独立 axios 实例）
 */
import axios, { type AxiosInstance } from 'axios'
import { backend } from './client'
import { ElMessage } from 'element-plus'
import { ResultCode } from '@/types/api'
import type {
  ApiResult,
  ShareLinkVO,
  CreateShareReq,
  PublicShareVO,
  PublicShareVerifyReq,
} from '@/types/api'

export type {
  ShareLinkVO,
  CreateShareReq,
  PublicShareVO,
  PublicShareVerifyReq,
  ShareScope,
  ShareStatus,
} from '@/types/api'

// ============== 公开端：独立 axios 实例（不挂 JWT，不拦截跳转登录） ==============

/** 公开端 axios：baseURL=/api，无 JWT 注入，401 不跳登录 */
export const publicBackend: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

publicBackend.interceptors.response.use(
  (r) => r,
  (e) => {
    const status = e?.response?.status
    const code = e?.response?.data?.code
    const msg = e?.response?.data?.message || e.message
    // 公开端 401/业务码 = 业务错误，提示即可，不跳登录
    if (status === 401 || code === ResultCode.TOKEN_INVALID) {
      ElMessage.error(msg || '链接无效')
    } else if (status >= 500) {
      ElMessage.error(msg || '服务器开了小差')
    } else if (status >= 400 && msg) {
      // 4xx 在页面里用 dialog 展示，不在这里 toast
    }
    return Promise.reject(e)
  },
)

// ============== Owner 端 ==============

/** 创建分享 */
export function createShare(projectId: string | number, data: CreateShareReq) {
  return backend.post<ApiResult<ShareLinkVO>>(
    `/v1/projects/${projectId}/shares`,
    data,
  )
}

/** 列分享 */
export function listShares(projectId: string | number) {
  return backend.get<ApiResult<ShareLinkVO[]>>(
    `/v1/projects/${projectId}/shares`,
  )
}

/** 撤销分享 */
export function revokeShare(shareId: string | number) {
  return backend.delete<ApiResult<void>>(`/v1/shares/${shareId}`)
}

// ============== 公开端 ==============

/** 公开预览（仅元信息：标题、是否需密码、是否过期） */
export function previewPublicShare(token: string) {
  return publicBackend.get<ApiResult<PublicShareVO>>(
    `/v1/public/shares/${token}`,
  )
}

/** 公开密码验证 + 取完整内容（password scope） */
export function verifyPublicShare(token: string, data: PublicShareVerifyReq) {
  return publicBackend.post<ApiResult<PublicShareVO>>(
    `/v1/public/shares/${token}/verify`,
    data,
  )
}

/** 公开取完整内容（public scope；password scope 走 verify） */
export function accessPublicShare(token: string) {
  return publicBackend.post<ApiResult<PublicShareVO>>(
    `/v1/public/shares/${token}/access`,
  )
}

// ============== PDF 导出 ==============

/** PDF 导出响应 */
export interface PdfExportResp {
  ossKey: string
  signedUrl: string
  expiresAt: number
  sizeBytes: number
  fromCache: boolean
}

/** Owner 端导出 PDF。 */
export function exportOwnerPdf(draftId: string) {
  return backend.get<ApiResult<PdfExportResp>>(`/v1/drafts/${draftId}/pdf`)
}

/** 公开分享端导出 PDF（password scope 需传 password）。 */
export function exportPublicPdf(token: string, password?: string) {
  const qs = password ? `?password=${encodeURIComponent(password)}` : ''
  return publicBackend.get<ApiResult<PdfExportResp>>(
    `/v1/public/shares/${token}/pdf${qs}`,
  )
}
