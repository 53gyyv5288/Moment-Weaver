/**
 * OSS STS 凭证 API。
 * 后端根据 yml aliyun.oss.sts.mode 决定返回 mock 还是 real 凭证。
 * 前端拿凭证后看 mode 自己决定走 OSS 直传还是 multipart 回退。
 */
import { backend } from './client'
import type { ApiResult } from '@/types/api'

export interface StsVO {
  mode: 'mock' | 'real' | string
  accessKeyId: string
  accessKeySecret: string
  securityToken: string
  expiration: string
  bucket: string
  region: string
  uploadPrefix: string
}

export function getSts() {
  return backend.get<ApiResult<StsVO>>('/v1/oss/sts')
}