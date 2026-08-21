/**
 * 家族关系图 API（家族级聚合）。
 *
 * 与 /api/v1/projects/{pid}/subjects/tree 不同：
 *   - 项目级：单项目内聚合，无去重
 *   - 家族级：跨项目聚合，按 family_member_id 自动合并同名节点
 */
import { backend } from './client'
import type { ApiResult, SubjectTreeResponse } from '@/types/api'

/** my-trees 响应元素 */
export interface MyFamilyTreeVO {
  familyId: string
  tree: SubjectTreeResponse
}

/** 当前用户加入的所有家族的家族树（前端 FamilyList 「家族树」tab 用） */
export function listMyFamilyTrees() {
  return backend.get<ApiResult<MyFamilyTreeVO[]>>('/v1/families/my-trees')
}

/** 单家族家族树（FamilyDetail 内嵌视图可用，前端 FamilyList 主用 my-trees） */
export function getFamilyTree(familyId: string | number) {
  return backend.get<ApiResult<SubjectTreeResponse>>(
    `/v1/families/${familyId}/tree`,
  )
}
