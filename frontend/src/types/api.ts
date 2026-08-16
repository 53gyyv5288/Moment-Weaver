/**
 * 与后端 Result<T> 对齐的 TS 类型。
 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T | null
  ts: number
}

export interface PageResult<T> {
  total: number
  page: number
  size: number
  records: T[]
}

/** 业务码（与后端 ResultCode 对齐） */
export enum ResultCode {
  SUCCESS = 0,

  // 1xxx 通用
  BAD_REQUEST = 1001,
  UNAUTHORIZED = 1002,
  FORBIDDEN = 1003,
  NOT_FOUND = 1004,
  CONFLICT = 1006,

  // 2xxx 账号 / 认证
  USER_NOT_FOUND = 2001,
  USER_ALREADY_EXISTS = 2002,
  PASSWORD_INCORRECT = 2003,
  TOKEN_INVALID = 2004,
  TOKEN_EXPIRED = 2005,

  // 3xxx 业务
  WORKSPACE_NOT_FOUND = 3001,
  PROJECT_NOT_FOUND = 3002,
  PROJECT_TYPE_INVALID = 3003,
  SUBJECT_NOT_FOUND = 3004,
  AUTHORIZATION_NOT_FOUND = 3005,
  AUTHORIZATION_INVALID = 3006,
  SHARE_TOKEN_INVALID = 3007,
  SHARE_LINK_NOT_FOUND = 3008,
  SHARE_LINK_EXPIRED = 3009,
  SHARE_LINK_REVOKED = 3010,
  SHARE_LINK_PASSWORD_INVALID = 3011,
  SHARE_LINK_RATE_LIMIT = 3012,
  SHARE_LINK_DRAFT_NOT_FOUND = 3013,
  NOTIFICATION_NOT_FOUND = 3020,
  EXPORT_REQUEST_NOT_FOUND = 3030,
  EXPORT_REQUEST_PENDING = 3031,
  EXPORT_REQUEST_FAILED = 3032,
  EXPORT_REQUEST_EXPIRED = 3033,
  DELETION_REQUEST_NOT_FOUND = 3040,
  DELETION_REQUEST_EXPIRED = 3041,
  DELETION_REQUEST_ALREADY_EXECUTED = 3042,
  DELETION_REQUEST_INVALID_SCOPE = 3043,
  CONSENT_VERSION_REQUIRED = 3050,
  CONSENT_VERSION_OUTDATED = 3051,
  AUDIT_LOG_NOT_FOUND = 3060,
  PDF_GENERATION_FAILED = 3070,
  PDF_DRAFT_NOT_PUBLISHED = 3071,
  PDF_FONT_NOT_FOUND = 3072,

  // 5xxx AI
  AI_UPSTREAM_ERROR = 5001,
  AI_CONTENT_BLOCKED = 5002,

  // 9xxx 系统
  SYSTEM_ERROR = 9999,
}

/** 判断业务码是否成功 */
export function isSuccess<T>(r: ApiResult<T>): boolean {
  return r.code === ResultCode.SUCCESS
}

// ============ M2 业务类型 ============

export interface SubjectVO {
  id: string
  projectId: string
  displayName: string
  relation?: string
  hasAccount: number
  linkedUserId?: string
  /** M11 Phase 2：关联的家族成员 id（NULL=匿名被采访者） */
  familyMemberId?: string
  /** 派生：家族成员的展示名（前端显示"采访家人"标签用） */
  familyMemberDisplayName?: string
  familyMemberAvatarUrl?: string
  note?: string
  latestAuthStatus?: string
  latestAuthId?: string
  createdAt?: string
  updatedAt?: string
}

export interface CreateSubjectReq {
  displayName: string
  relation?: string
  note?: string
  /**
   * M11 Phase 2：可选的家族成员 id。
   * 传非空 → 从家族成员里选（个人项目不支持）；
   * 不传 → 纯匿名被采访者（displayName 必填）
   */
  familyMemberId?: string | number | null
}

/**
 * M11 Phase 2：项目下"可选被采访者"（从家族成员里筛）。
 * 用于前端"添加人物"弹窗 Tab 1。
 */
export interface EligibleFamilyMemberVO {
  familyMemberId: string
  userId?: string
  displayName: string
  phone?: string | null
  email?: string | null
  avatarUrl?: string | null
  /** admin / editor / viewer */
  role: 'admin' | 'editor' | 'viewer'
  /** true = 已经是本项目的被采访者（重复添加检测） */
  hasSubject: boolean
  existingSubjectId?: string
}

/** 局部更新人物。所有字段都可空；不传 = 不变；显式传 "" = 清空 */
export interface UpdateSubjectReq {
  displayName?: string
  relation?: string
  note?: string
}

export interface AuthorizationVO {
  id: string
  subjectId: string
  projectId: string
  token: string
  scopes: string[]
  status: 'pending' | 'granted' | 'denied' | 'revoked' | 'expired' | string
  consentVersion: string
  grantedAt?: string
  revokedAt?: string
  expiresAt?: string
  publicUrl?: string
  createdAt?: string
}

export interface CreateAuthorizationReq {
  subjectId: string | number
  scopes: string[]
  ttlDays?: number
}

export interface InterviewMessageVO {
  role: 'system' | 'user' | 'assistant' | string
  content: string
  /** 思考链：推理模型 <think>...</think> 内容；非推理模型 / 老文档为 undefined */
  thinking?: string
  source?: 'human' | 'ai_generated' | string
  createdAt?: string
  /**
   * RAG 检索到的跨 session 历史片段（最多 5 条；中途推或下轮前置推）。
   * 仅 assistant 消息可能携带；老文档没有。
   */
  evidence?: InterviewEvidenceItem[]
  /**
   * Turn 配对 ID（M8+）：同一对的 user + assistant 共享同一个 turnId；
   * system 消息此字段为 undefined。
   * 用作 v-for :key 让 Vue 渲染更稳定（比 idx 更鲁棒，避免插入时复用错位）。
   */
  turnId?: string
  /**
   * Turn 状态（M8+）：仅 user 消息有意义。
   * - PENDING：user 已落库，等 assistant（流进行中）
   * - COMPLETED：user + assistant 都到位
   * - FAILED：流中断 / 错误，assistant 永远缺席
   * 老文档此字段为 undefined，UI 应兜底按 COMPLETED 处理。
   */
  turnStatus?: 'PENDING' | 'COMPLETED' | 'FAILED'
}

/** RAG 检索片段（前后端 snake_case 一致） */
export interface InterviewEvidenceItem {
  sessionId: string
  text: string
  score: number
}

export interface InterviewSessionVO {
  id: string
  projectId: string
  subjectId: string
  authorizationId: string
  status: 'active' | 'closed' | string
  subjectDisplayName?: string
  projectName?: string
  messages: InterviewMessageVO[]
  startedAt?: string
  lastMessageAt?: string
  closedAt?: string
  /** M11 Phase 3：启动 session 的用户 id（NULL=公开 token） */
  startedByUserId?: string
  /** M11 Phase 3：当前用户是否能进入采访房间说话（userB / 公开 token） */
  canStream?: boolean
  /** 结构化摘要（采访结束后生成） */
  summary?: InterviewSummaryVO | null
}

// ============ M3 业务类型 ============

/** 采访会话的结构化摘要 */
export interface InterviewSummaryVO {
  title: string
  goldenQuotes: string[]
  keyMoments: { timestamp: string; text: string }[]
  generatedAt: string
}

/** 素材 */
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

/** 时间线条目 */
export interface TimelineItemVO {
  id: string
  projectId: string
  subjectId?: string
  type:
    | 'interview_message'
    | 'asset_uploaded'
    | 'ai_summary'
    | 'narrative_draft_created'
    | 'narrative_draft_section_edited'
    | 'narrative_draft_published'
    | string
  eventAt: string
  refId?: string
  title: string
  preview?: string
  metadata?: Record<string, unknown>
}

// ============ M4 业务类型 ============

/** 章节来源标识 */
export type SectionProvenance = 'ai' | 'human' | 'mixed' | 'system' | string

/** 重写风格 */
export type RewriteStyle = 'warmer' | 'concise' | 'vivid' | 'formal' | string

/** 章节 VO */
export interface SectionVO {
  sectionId: string
  sectionTitle: string
  order: number
  targetCharsMin?: number
  targetCharsMax?: number
  markPolicy?: string
  content: string
  provenance: SectionProvenance
  aiGenerated: boolean
  factsUsed?: string[]
  lastRewriteStyle?: RewriteStyle | null
  rewriteCount: number
  manuallyEditedAt?: string | null
}

/** 事实快照 VO */
export interface FactSnapshotVO {
  factId: string
  source: 'interview' | 'asset_caption' | 'note' | string
  text: string
  subjectId: string
  timestamp?: string
}

/** 成稿 VO */
export interface NarrativeDraftVO {
  id: string
  projectId: string
  workspaceId?: string
  ownerId?: string
  templateId: string
  scope: 'person' | 'family' | string
  subjectIds: string[]
  subjectDisplayNames?: string[]
  title: string
  status: 'pending' | 'draft' | 'published' | 'archived' | string
  sections: SectionVO[]
  factsSnapshot?: FactSnapshotVO[]
  createdAt?: string
  updatedAt?: string
  publishedAt?: string | null
  version: number
}

/** 创建成稿请求 */
export interface CreateDraftReq {
  templateId: string
  scope: 'person' | 'family'
  subjectIds: string[]
  title?: string
}

/** 更新单章节请求 */
export interface UpdateSectionReq {
  content?: string
  rewriteStyle?: RewriteStyle
}

/** 发布成稿请求 */
export interface PublishDraftReq {
  // 当前没有必填字段；保留 title / cover 以备 M5 扩展
  title?: string
  cover?: string
}

// ============ M5 业务类型 ============

/** 分享 scope */
export type ShareScope = 'public' | 'password'

/** 分享状态 */
export type ShareStatus = 'active' | 'expired' | 'revoked'

/** Owner 端分享链接 VO */
export interface ShareLinkVO {
  id: string
  projectId: string
  draftId: string
  draftTitle?: string
  scope: ShareScope
  /** 32 字符 URL-safe base64 token */
  token: string
  shareUrl: string
  allowCopy: boolean
  allowDownload: boolean
  viewCount: number
  createdByName?: string
  createdAt?: string
  expiresAt?: string
  lastAccessedAt?: string
  status: ShareStatus
  hasPassword: boolean
}

/** 创建分享请求 */
export interface CreateShareReq {
  draftId: string | number
  scope: ShareScope
  password?: string
  expiresInDays?: number
  allowCopy?: boolean
  allowDownload?: boolean
  subjectIds?: string[]
}

/** 公开端章节（精简版，不带 factsUsed 等内部字段） */
export interface PublicSectionVO {
  sectionId: string
  sectionTitle: string
  order: number
  content: string
  provenance: SectionProvenance
  aiGenerated: boolean
}

/** 公开端分享 VO（不需 JWT） */
export interface PublicShareVO {
  token: string
  draftId: string
  draftTitle?: string
  scope: ShareScope
  allowCopy: boolean
  allowDownload: boolean
  createdByName?: string
  createdAt?: string
  expiresAt?: string
  hasAiContent: boolean
  aiLabel?: string
  sections?: PublicSectionVO[]
}

/** 公开端密码验证请求 */
export interface PublicShareVerifyReq {
  password: string
}

