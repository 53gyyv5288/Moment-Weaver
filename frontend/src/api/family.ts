/**
 * 家族 API。
 * 后端路由：/api/v1/families/*
 */
import { backend } from './client'
import type { ApiResult } from '@/types/api'

export type FamilyRole = 'admin' | 'editor' | 'viewer'

export interface FamilyVO {
  id: string
  name: string
  description?: string | null
  ownerUserId: string
  myRole: FamilyRole
  memberCount?: number
  projectCount?: number
  createdAt: string
  updatedAt?: string
}

export interface FamilyMemberVO {
  userId: string
  displayName: string
  phone?: string | null
  email?: string | null
  avatarUrl?: string | null
  role: FamilyRole
  joinedAt: string
}

export interface CreateFamilyRequest {
  name: string
  description?: string
}

export interface UpdateFamilyRequest {
  name?: string
  description?: string
}

export interface AdminCreateUserRequest {
  displayName: string
  phone?: string
  email?: string
  password: string
  role: FamilyRole
}

export interface AdminCreateUserResponse {
  userId: string
  displayName: string
  initialPassword: string  // 一次性明文密码
  role: FamilyRole
  mustChangePassword: boolean
}

export interface UpdateFamilyMemberRequest {
  role: FamilyRole
  resetPassword?: string
}

// ===== 家族 CRUD =====

export function createFamily(data: CreateFamilyRequest) {
  return backend.post<ApiResult<FamilyVO>>('/v1/families', data)
}

export function listFamilies() {
  return backend.get<ApiResult<FamilyVO[]>>('/v1/families')
}

export function getFamily(id: string | number) {
  return backend.get<ApiResult<FamilyVO>>(`/v1/families/${id}`)
}

export function updateFamily(id: string | number, data: UpdateFamilyRequest) {
  return backend.put<ApiResult<FamilyVO>>(`/v1/families/${id}`, data)
}

export function listFamilyProjects(id: string | number) {
  return backend.get<ApiResult<import('./project').ProjectVO[]>>(`/v1/families/${id}/projects`)
}

// ===== 成员管理 =====

export function listFamilyMembers(id: string | number) {
  return backend.get<ApiResult<FamilyMemberVO[]>>(`/v1/families/${id}/members`)
}

export function adminCreateMember(id: string | number, data: AdminCreateUserRequest) {
  return backend.post<ApiResult<AdminCreateUserResponse>>(`/v1/families/${id}/members`, data)
}

export function updateFamilyMember(
  id: string | number,
  userId: string | number,
  data: UpdateFamilyMemberRequest,
) {
  return backend.put<ApiResult<FamilyMemberVO>>(
    `/v1/families/${id}/members/${userId}`,
    data,
  )
}

export function removeFamilyMember(id: string | number, userId: string | number) {
  return backend.delete<ApiResult<void>>(`/v1/families/${id}/members/${userId}`)
}
