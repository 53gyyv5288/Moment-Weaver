/**
 * 账号 / 认证 API。
 * 后端路由：/api/v1/auth/*
 */
import { backend } from './client'
import type { ApiResult } from '@/types/api'

export interface RegisterRequest {
  account: string // 手机号或邮箱
  password: string
  displayName: string
}

export interface LoginRequest {
  account: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  user: UserVO
}

export interface UserVO {
  id: string
  phone: string | null
  email: string | null
  displayName: string
  avatarUrl: string | null
  status: number
  createdAt: string
  /** M10+ Family：是否家族管理员 */
  isFamilyAdmin?: boolean
  /** M10+ Family：是否需要强制改密 */
  mustChangePassword?: boolean
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

export function register(data: RegisterRequest) {
  return backend.post<ApiResult<LoginResponse>>('/v1/auth/register', data)
}

export function login(data: LoginRequest) {
  return backend.post<ApiResult<LoginResponse>>('/v1/auth/login', data)
}

export function me() {
  return backend.get<ApiResult<UserVO>>('/v1/auth/me')
}

export function logout() {
  return backend.post<ApiResult<null>>('/v1/auth/logout')
}

export function changePassword(data: ChangePasswordRequest) {
  return backend.post<ApiResult<null>>('/v1/auth/change-password', data)
}
