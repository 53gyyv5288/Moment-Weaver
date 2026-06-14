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
  source?: 'human' | 'ai_generated' | string
  createdAt?: string
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
}
