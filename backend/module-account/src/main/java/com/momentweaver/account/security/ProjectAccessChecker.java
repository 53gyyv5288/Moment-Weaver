package com.momentweaver.account.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.FamilyMember;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.FamilyMemberMapper;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 项目级访问校验（M10+ Family）。
 *
 * <p>设计要点：
 *   <ul>
 *     <li>项目有 {@code familyId}（家族项目） → 校验 family_member 表</li>
 *     <li>项目无 {@code familyId}（个人项目） → 校验 workspace_member 表</li>
 *   </ul>
 *
 * <p>这是"业务模块（SubjectService / InterviewService / AuthorizationService）"
 * 应该调用的统一入口，替代它们自己写的 {@code ensureMember(workspaceId, userId)}。
 *
 * <p>位置：与 {@link WorkspaceAccessChecker}、{@link FamilyAccessChecker} 同位于
 * module-account.security 包，业务模块已经依赖 module-account，无需新增依赖。
 */
@Component
@RequiredArgsConstructor
public class ProjectAccessChecker {

    private final ProjectMapper projectMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final FamilyMemberMapper familyMemberMapper;

    /**
     * 校验 userId 能访问 projectId（读权限）。
     * 失败抛 FORBIDDEN。
     */
    public void requireMember(Long projectId, Long userId) {
        Project p = mustProject(projectId);
        if (p.getFamilyId() != null) {
            requireFamilyMember(p.getFamilyId(), userId);
        } else {
            requireWorkspaceMember(p.getWorkspaceId(), userId);
        }
    }

    /**
     * 校验 userId 能写 projectId（写权限）。
     * 家族项目：family_member.role != viewer
     * 个人项目：workspace_member（任意角色均可写）
     */
    public void requireEditor(Long projectId, Long userId) {
        Project p = mustProject(projectId);
        if (p.getFamilyId() != null) {
            requireFamilyEditor(p.getFamilyId(), userId);
        } else {
            requireWorkspaceMember(p.getWorkspaceId(), userId);
        }
    }

    /**
     * 校验 userId 是 projectId 的 Owner（删项目等高权限操作）。
     * 个人项目：Project.owner_id
     * 家族项目：family.owner_user_id（家族管理员 = 家族项目 Owner）
     */
    public void requireOwner(Long projectId, Long userId) {
        Project p = mustProject(projectId);
        if (p.getFamilyId() != null) {
            // 家族项目：用 family_member.role = admin 校验
            FamilyMember m = familyMemberMapper.selectOne(
                new LambdaQueryWrapper<FamilyMember>()
                    .eq(FamilyMember::getFamilyId, p.getFamilyId())
                    .eq(FamilyMember::getUserId, userId)
            );
            if (m == null || !"admin".equals(m.getRole())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "仅家族管理员可操作");
            }
        } else {
            // 个人项目：Project.owner_id 校验
            if (!p.getOwnerId().equals(userId)) {
                throw new BusinessException(ResultCode.FORBIDDEN, "仅项目 Owner 可操作");
            }
        }
    }

    // ---- helpers ----

    private Project mustProject(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        return p;
    }

    private void requireWorkspaceMember(Long workspaceId, Long userId) {
        Long cnt = workspaceMemberMapper.selectCount(
            new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId)
        );
        if (cnt == null || cnt == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非工作区成员");
        }
    }

    private void requireFamilyMember(Long familyId, Long userId) {
        FamilyMember m = familyMemberMapper.selectOne(
            new LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getUserId, userId)
        );
        if (m == null) {
            throw new BusinessException(ResultCode.FAMILY_NOT_MEMBER);
        }
    }

    private void requireFamilyEditor(Long familyId, Long userId) {
        FamilyMember m = familyMemberMapper.selectOne(
            new LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getUserId, userId)
        );
        if (m == null) {
            throw new BusinessException(ResultCode.FAMILY_NOT_MEMBER);
        }
        if ("viewer".equals(m.getRole())) {
            throw new BusinessException(ResultCode.FAMILY_VIEWER_READONLY);
        }
    }
}
