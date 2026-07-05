package com.momentweaver.account.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M5 抽取的统一鉴权组件。
 *
 * <p>背景：M1~M4 共有 7 个 service 自己写 {@code ensureMember(workspaceId, userId)}，
 * 重复度高且容易漏掉 owner 校验。M5 新写的 service 一律用本组件。
 * M4 及之前的旧代码不强行改造（避免大爆炸），仅在原方法上留 TODO 注释。
 *
 * <p>位置说明：与 {@link CurrentUser} 同位于 module-account.security 包，
 * 各业务模块已经依赖 module-account，无需新增依赖。
 */
@Component
@RequiredArgsConstructor
public class WorkspaceAccessChecker {

    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final ProjectMapper projectMapper;

    /**
     * 校验 userId 是 workspaceId 的成员（owner / editor / viewer 任一角色）。
     */
    public void requireMember(Long workspaceId, Long userId) {
        if (workspaceId == null || userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long count = workspaceMemberMapper.selectCount(new LambdaQueryWrapper<WorkspaceMember>()
            .eq(WorkspaceMember::getWorkspaceId, workspaceId)
            .eq(WorkspaceMember::getUserId, userId));
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非工作区成员");
        }
    }

    /**
     * 校验 userId 是 workspaceId 的 owner。
     */
    public void requireOwner(Long workspaceId, Long userId) {
        if (workspaceId == null || userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long count = workspaceMemberMapper.selectCount(new LambdaQueryWrapper<WorkspaceMember>()
            .eq(WorkspaceMember::getWorkspaceId, workspaceId)
            .eq(WorkspaceMember::getUserId, userId)
            .eq(WorkspaceMember::getRole, "owner"));
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅工作区 owner 可操作");
        }
    }

    /**
     * 校验 userId 是 projectId 所属工作区的成员。
     */
    public void requireProjectMember(Long projectId, Long userId) {
        Long workspaceId = resolveWorkspaceId(projectId);
        requireMember(workspaceId, userId);
    }

    /**
     * 校验 userId 是 projectId 的 owner。
     */
    public void requireProjectOwner(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
        if (!p.getOwnerId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅项目 owner 可操作");
        }
    }

    /**
     * 仅解析 projectId → workspaceId，不做权限校验。
     */
    public Long resolveWorkspaceId(Long projectId) {
        if (projectId == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
        return p.getWorkspaceId();
    }

    /**
     * 取 workspaceId 的所有成员 userId 列表。
     * 通知中心用：发布 draft 时通知所有 project member。
     */
    public List<Long> listMemberIds(Long workspaceId) {
        if (workspaceId == null) return List.of();
        return workspaceMemberMapper.selectList(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId))
            .stream()
            .map(WorkspaceMember::getUserId)
            .toList();
    }
}
