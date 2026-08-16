/**
 * 家族项目权限判断 composable。
 *
 * 用法（必须在 setup() 里调用，因为需要传 inject 出来的 project）：
 * ```ts
 * import { inject } from 'vue'
 * import { useProjectPermission } from '@/composables/useProjectPermission'
 *
 * const project = inject<Ref<ProjectVO | null>>('project')!
 * const { canEdit, canManage, isReadonly, role } = useProjectPermission(project)
 * ```
 *
 * 返回的权限判断规则：
 *   - 家族项目：
 *       admin  → canEdit=true, canManage=true, isReadonly=false
 *       editor → canEdit=true, canManage=false, isReadonly=false
 *       viewer → canEdit=false, canManage=false, isReadonly=true
 *   - 个人项目（familyId=null）：
 *       role=null  → canEdit=true, canManage=false, isReadonly=false
 *         （前端放行让按钮显示；后端 requireOwner 校验项目 owner）
 *
 * 关联：ProjectService.toVO 已注入 myPermission，后端每种项目都会带这个字段。
 */
import { computed, type ComputedRef, type Ref } from 'vue'
import type { ProjectVO } from '@/api/project'

export type PermissionRole = 'admin' | 'editor' | 'viewer' | null

export interface ProjectPermission {
  role: ComputedRef<PermissionRole>
  /** 写权限：admin + editor；个人项目默认可编辑 */
  canEdit: ComputedRef<boolean>
  /** 管理权限：admin（家族）；个人项目默认可管理 */
  canManage: ComputedRef<boolean>
  /** 只读：仅家族 viewer */
  isReadonly: ComputedRef<boolean>
}

export function useProjectPermission(project: Ref<ProjectVO | null>): ProjectPermission {
  const role = computed<PermissionRole>(() => {
    if (!project.value) return null
    const r = project.value.myPermission
    if (r === 'admin' || r === 'editor' || r === 'viewer') return r
    return null
  })

  const isFamilyProject = computed(() => !!project.value?.familyId)

  const canEdit = computed(() => {
    if (!project.value) return false
    if (!isFamilyProject.value) return true  // 个人项目：前端放行
    return role.value === 'admin' || role.value === 'editor'
  })

  const canManage = computed(() => {
    if (!project.value) return false
    if (!isFamilyProject.value) return true  // 个人项目 owner
    return role.value === 'admin'
  })

  const isReadonly = computed(() => {
    if (!project.value) return false
    return isFamilyProject.value && role.value === 'viewer'
  })

  return { role, canEdit, canManage, isReadonly }
}
