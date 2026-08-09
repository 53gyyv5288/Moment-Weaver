/**
 * 项目 API。
 * 后端路由：/api/v1/projects/*
 */
import { backend } from './client'
import type { ApiResult, PageResult } from '@/types/api'

export type ProjectType = 'family' | 'personal'

export interface ProjectCreateRequest {
  type: ProjectType
  name: string
  description?: string
  workspaceId?: string // 缺省时使用用户默认工作区
}

export interface ProjectVO {
  id: string
  workspaceId: string
  ownerId: string
  type: ProjectType
  name: string
  description: string | null
  status: number
  createdAt: string
  updatedAt: string
}

export interface ProjectListQuery {
  workspaceId?: string
  type?: ProjectType
  page?: number
  size?: number
}

export function createProject(data: ProjectCreateRequest) {
  return backend.post<ApiResult<ProjectVO>>('/v1/projects', data)
}

export function listProjects(query: ProjectListQuery = {}) {
  return backend.get<ApiResult<PageResult<ProjectVO>>>('/v1/projects', { params: query })
}

export function getProject(id: string | number) {
  return backend.get<ApiResult<ProjectVO>>(`/v1/projects/${id}`)
}

export function deleteProject(id: string | number) {
  return backend.delete<ApiResult<null>>(`/v1/projects/${id}`)
}

/** 修改项目名称 / 描述（PUT 部分更新，name/description 至少传一项） */
export interface ProjectUpdateRequest {
  name?: string
  description?: string
}

export function updateProject(id: string | number, data: ProjectUpdateRequest) {
  return backend.put<ApiResult<ProjectVO>>(`/v1/projects/${id}`, data)
}
