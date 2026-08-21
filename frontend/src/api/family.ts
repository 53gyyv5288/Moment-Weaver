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
  id: string
  familyId: string
  userId: string
  displayName: string
  phone?: string | null
  email?: string | null
  avatarUrl?: string | null
  role: FamilyRole
  joinedAt: string
  // ============ M14+ 家族关系图 ============
  /** 代际：正数=长辈，0=本人辈，负数=晚辈；NULL=未分代 */
  generation?: number | null
  /** 上一代 family_member.id（同家族内）；NULL=上一代不在家族里 */
  parentFamilyMemberId?: string | null
  /** 与上一代的关系类型：father|mother|guardian */
  parentMemberRelationType?: 'father' | 'mother' | 'guardian' | null
  /** 派生：上一代 family_member 的 displayName（渲染连线标签用） */
  parentDisplayName?: string | null
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
  // M14+ 家族关系图：创建家族成员时一次性录入代际 + 上一代
  generation?: number | null
  parentFamilyMemberId?: string | number | null
  parentMemberRelationType?: 'father' | 'mother' | 'guardian' | null
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
  // M14+ 家族关系图：哨兵值同 Subject —— null=不变；-50/-1=清空
  generation?: number | null
  parentFamilyMemberId?: string | number | null
  parentMemberRelationType?: 'father' | 'mother' | 'guardian' | '' | null
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
