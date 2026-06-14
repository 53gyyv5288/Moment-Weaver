/**
 * 智能上传：根据 STS.mode 决定走 OSS 直传还是 multipart 后端中转。
 *
 * <p>real 模式：先调 STS → 拿到 bucket/region/ak/sk/token → 浏览器 PUT 到 OSS → 成功后回调 create。
 * <p>mock 模式：直接 multipart 上传到后端，由后端落本地。
 *
 * <p>失败处理：real 模式下若 STS 拿到但直传 OSS 失败，自动 fallback 到 multipart，
 * 避免在开发环境因 RAM 角色未配好而完全无法上传。
 */
import { getSts } from '@/api/oss'
import { createAsset, uploadMock, type AssetVO } from '@/api/asset'

export interface SmartUploadOpts {
  projectId: string | number
  subjectId?: number
  interviewId?: string
  caption?: string
  onProgress?: (loaded: number, total: number) => void
}

export async function smartUpload(file: File, opts: SmartUploadOpts): Promise<AssetVO> {
  // 1) 拿 STS
  const { data: stsResp } = await getSts()
  if (!stsResp || stsResp.code !== 0 || !stsResp.data) {
    throw new Error(stsResp?.message || 'STS 获取失败')
  }
  const sts = stsResp.data

  // 2) 决定路径
  if (sts.mode === 'real') {
    try {
      return await uploadToOss(file, sts, opts)
    } catch (e) {
      console.warn('[smartUpload] OSS direct upload failed, fallback to multipart:', e)
      // fallback
    }
  }
  return await uploadMultipart(file, opts)
}

async function uploadToOss(
  file: File,
  sts: {
    accessKeyId: string
    accessKeySecret: string
    securityToken: string
    bucket: string
    region: string
    uploadPrefix: string
  },
  opts: SmartUploadOpts,
): Promise<AssetVO> {
  // 动态 import：ali-oss 体积较大且仅 real 模式需要；mock 模式不打进 bundle 也行
  // （dynamic import 在 Vite 中会单独 chunk，但 dev 阶段仍会加载）
  const OSS = (await import('ali-oss')).default

  const client = new OSS({
    accessKeyId: sts.accessKeyId,
    accessKeySecret: sts.accessKeySecret,
    stsToken: sts.securityToken,
    bucket: sts.bucket,
    region: sts.region, // ali-oss 需要带 oss- 前缀的完整 region ID（如 oss-cn-guangzhou）
    secure: true,
  })

  // key = uploadPrefix/{yyyy/MM/dd}/{uuid}{ext}
  const today = new Date()
  const ymd = `${today.getFullYear()}/${String(today.getMonth() + 1).padStart(2, '0')}/${String(today.getDate()).padStart(2, '0')}`
  const uuid = crypto.randomUUID().replace(/-/g, '')
  const ext = file.name.includes('.') ? file.name.substring(file.name.lastIndexOf('.')) : ''
  const key = `${sts.uploadPrefix}/${ymd}/${uuid}${ext}`

  // PUT 上传（ali-oss 在浏览器里走 multipart 不友好，直接 put）
  const res = await client.put(key, file, {
    progress: (p: number) => {
      if (opts.onProgress) opts.onProgress(Math.round(p * file.size), file.size)
    },
  })
  if (!res || !res.res || res.res.status !== 200) {
    throw new Error(`OSS PUT failed: ${res?.res?.status}`)
  }

  // 回调后端登记 metadata
  const kind = file.type.startsWith('image/') ? 'image' : file.type.startsWith('audio/') ? 'audio' : 'image'
  const { data: created } = await createAsset(opts.projectId, {
    kind,
    storage: 'oss',
    ossKey: key,
    ossBucket: sts.bucket,
    ossRegion: sts.region,
    originalName: file.name,
    mimeType: file.type,
    sizeBytes: file.size,
    subjectId: opts.subjectId,
    interviewId: opts.interviewId,
    caption: opts.caption,
  })
  if (!created || created.code !== 0 || !created.data) {
    throw new Error(created?.message || '登记 metadata 失败')
  }
  return created.data!
}

async function uploadMultipart(file: File, opts: SmartUploadOpts): Promise<AssetVO> {
  const { data } = await uploadMock(opts.projectId, file, {
    subjectId: opts.subjectId,
    interviewId: opts.interviewId,
    caption: opts.caption,
    onProgress: (e) => opts.onProgress?.(e.loaded, e.total),
  })
  if (!data || data.code !== 0 || !data.data) {
    throw new Error(data?.message || '上传失败')
  }
  return data.data!
}