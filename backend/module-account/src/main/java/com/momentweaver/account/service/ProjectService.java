package com.momentweaver.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.momentweaver.account.dto.ProjectCreateRequest;
import com.momentweaver.account.dto.ProjectVO;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.Workspace;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.WorkspaceMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.PageResult;
import com.momentweaver.common.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;

    @Transactional
    public ProjectVO create(Long userId, ProjectCreateRequest req) {
        Long workspaceId = defaultWorkspaceIdOf(userId);

        Project p = new Project();
        p.setWorkspaceId(workspaceId);
        p.setOwnerId(userId);
        p.setType(req.getType());
        p.setName(req.getName());
        p.setDescription(req.getDescription());
        p.setStatus(1);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(p);
        return toVO(p);
    }

    public PageResult<ProjectVO> list(Long userId, long page, long size) {
        Long workspaceId = defaultWorkspaceIdOf(userId);
        IPage<Project> p = projectMapper.selectPage(
            Page.of(page, size),
            new LambdaQueryWrapper<Project>()
                .eq(Project::getWorkspaceId, workspaceId)
                .orderByDesc(Project::getUpdatedAt)
        );
        List<ProjectVO> vos = p.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(p.getTotal(), p.getCurrent(), p.getSize(), vos);
    }

    public ProjectVO get(Long userId, Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        ensureMember(p.getWorkspaceId(), userId);
        return toVO(p);
    }

    @Transactional
    public void delete(Long userId, Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        if (!p.getOwnerId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅 Owner 可删除项目");
        }
        projectMapper.deleteById(projectId);
    }

    // ---- helpers ----

    private Long defaultWorkspaceIdOf(Long userId) {
        WorkspaceMember wm = workspaceMemberMapper.selectOne(
            new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId)
        );
        if (wm == null) throw new BusinessException(ResultCode.WORKSPACE_NOT_FOUND);
        return wm.getWorkspaceId();
    }

    private void ensureMember(Long workspaceId, Long userId) {
        Long cnt = workspaceMemberMapper.selectCount(
            new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId)
        );
        if (cnt == null || cnt == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非工作区成员");
        }
    }

    private ProjectVO toVO(Project p) {
        ProjectVO vo = new ProjectVO();
        vo.setId(p.getId());
        vo.setWorkspaceId(p.getWorkspaceId());
        vo.setOwnerId(p.getOwnerId());
        vo.setType(p.getType());
        vo.setName(p.getName());
        vo.setDescription(p.getDescription());
        vo.setStatus(p.getStatus());
        vo.setCreatedAt(p.getCreatedAt());
        vo.setUpdatedAt(p.getUpdatedAt());
        return vo;
    }
}
