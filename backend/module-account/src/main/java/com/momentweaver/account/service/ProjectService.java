package com.momentweaver.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.momentweaver.account.dto.ProjectCreateRequest;
import com.momentweaver.account.dto.ProjectUpdateRequest;
import com.momentweaver.account.dto.ProjectVO;
import com.momentweaver.account.entity.FamilyMember;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.entity.User;
import com.momentweaver.account.mapper.FamilyMemberMapper;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.UserMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.account.security.FamilyAccessChecker;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.PageResult;
import com.momentweaver.common.ResultCode;
import com.momentweaver.common.event.ProjectCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final FamilyMemberMapper familyMemberMapper;
    private final FamilyAccessChecker familyAccessChecker;
    /** M12+：取用户昵称用作个人项目默认 subject.displayName */
    private final UserMapper userMapper;
    /** M12+：发 ProjectCreatedEvent（个人项目 bootstrap 用） */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建项目（兼容 + 扩展）：
     * <ul>
     *   <li>req.familyId == null   → 创建个人项目（挂在默认 workspace 下，原样保留）</li>
     *   <li>req.familyId != null   → 创建家族项目（要求当前 user 是该家族成员）</li>
     * </ul>
     *
     * <p>兼容性保证：旧调用方不传 familyId，行为与改造前完全一致。
     */
    @Transactional
    public ProjectVO create(Long userId, ProjectCreateRequest req) {
        Long familyId = req.getFamilyId();
        if (familyId != null) {
            // 家族项目：要求成员身份（admin/editor 可创建；viewer 不可）
            familyAccessChecker.requireEditor(familyId, userId);
        }

        // 修复：家族项目不需要 workspace（管理员创建的成员账号也可能没 workspace）
        // 个人项目必须用默认 workspace
        Long workspaceId = null;
        if (familyId == null) {
            workspaceId = defaultWorkspaceIdOf(userId);
        }

        Project p = new Project();
        p.setWorkspaceId(workspaceId);  // 家族项目为 NULL（后端 select/写入都不依赖）
        p.setOwnerId(userId);
        p.setFamilyId(familyId);  // 可能为 null（个人项目）
        p.setType(req.getType());
        p.setName(req.getName());
        p.setDescription(req.getDescription());
        p.setStatus(1);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(p);

        // M12+：个人项目（familyId == null）→ 发 ProjectCreatedEvent，
        // PersonalProjectBootstrapListener 自动建"我本人"subject + 自授权 granted 记录
        if (familyId == null) {
            String displayName = null;
            User u = userMapper.selectById(userId);
            if (u != null && u.getDisplayName() != null && !u.getDisplayName().isBlank()) {
                displayName = u.getDisplayName();
            }
            eventPublisher.publishEvent(new ProjectCreatedEvent(
                userId, p.getId(), p.getFamilyId(), displayName));
            log.info("Project created (personal): projectId={} ownerId={}", p.getId(), userId);
        }

        return toVO(p, userId);
    }

    /**
     * 项目列表。
     *
     * <p>返回规则：
     *   <ul>
     *     <li>个人项目：owner_id = userId</li>
     *     <li>家族项目：family_id IN (我加入的所有家族)</li>
     *   </ul>
     *
     * <p>用 OR 拼接，一次查询返回。
     */
    public PageResult<ProjectVO> list(Long userId, long page, long size) {
        List<Long> familyIds = familyIdsOf(userId);

        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        if (familyIds.isEmpty()) {
            wrapper.eq(Project::getOwnerId, userId);
        } else {
            wrapper.and(w -> w.eq(Project::getOwnerId, userId)
                .or().in(Project::getFamilyId, familyIds));
        }
        wrapper.orderByDesc(Project::getUpdatedAt);

        IPage<Project> p = projectMapper.selectPage(Page.of(page, size), wrapper);
        List<ProjectVO> vos = p.getRecords().stream().map(proj -> toVO(proj, userId)).toList();
        return new PageResult<>(p.getTotal(), p.getCurrent(), p.getSize(), vos);
    }

    public ProjectVO get(Long userId, Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);

        if (p.getFamilyId() != null) {
            // 家族项目：必须家族成员才能看
            familyAccessChecker.requireMember(p.getFamilyId(), userId);
        } else {
            // 个人项目：原 workspace 成员校验（兼容旧逻辑）
            ensureWorkspaceMember(p.getWorkspaceId(), userId);
        }
        return toVO(p, userId);
    }

    @Transactional
    public void delete(Long userId, Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);

        if (p.getFamilyId() != null) {
            // 家族项目：仅家族 admin 可删（owner 不够，避免 editor 误删）
            familyAccessChecker.requireAdmin(p.getFamilyId(), userId);
        } else {
            // 个人项目：仅 owner 可删（保留原行为）
            if (!p.getOwnerId().equals(userId)) {
                throw new BusinessException(ResultCode.FORBIDDEN, "仅 Owner 可删除项目");
            }
        }
        projectMapper.deleteById(projectId);
    }

    @Transactional
    public ProjectVO update(Long userId, Long projectId, ProjectUpdateRequest req) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);

        if (p.getFamilyId() != null) {
            // 家族项目：admin/editor 可改
            familyAccessChecker.requireEditor(p.getFamilyId(), userId);
        } else {
            ensureWorkspaceMember(p.getWorkspaceId(), userId);
        }

        if (req.getName() != null) {
            p.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            p.setDescription(req.getDescription().isEmpty() ? null : req.getDescription().trim());
        }
        p.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(p);
        return toVO(p, userId);
    }

    // ---- helpers ----

    /**
     * 默认 workspaceId（个人项目容器）。每个用户注册时自动建一个。
     */
    private Long defaultWorkspaceIdOf(Long userId) {
        WorkspaceMember wm = workspaceMemberMapper.selectOne(
            new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId)
        );
        if (wm == null) throw new BusinessException(ResultCode.WORKSPACE_NOT_FOUND);
        return wm.getWorkspaceId();
    }

    private List<Long> familyIdsOf(Long userId) {
        return familyMemberMapper.selectList(
            new LambdaQueryWrapper<FamilyMember>().eq(FamilyMember::getUserId, userId)
        ).stream().map(FamilyMember::getFamilyId).collect(Collectors.toList());
    }

    private void ensureWorkspaceMember(Long workspaceId, Long userId) {
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
        return toVO(p, null);
    }

    /**
     * M11 Phase 3：填充 myPermission 字段。
     * <ul>
     *   <li>家族项目：user 在 family_member 里的 role（admin/editor/viewer）</li>
     *   <li>个人项目：null（前端按"个人项目"显示所有按钮）</li>
     * </ul>
     */
    private ProjectVO toVO(Project p, Long userId) {
        ProjectVO vo = new ProjectVO();
        vo.setId(p.getId());
        vo.setWorkspaceId(p.getWorkspaceId());
        vo.setOwnerId(p.getOwnerId());
        vo.setFamilyId(p.getFamilyId());
        vo.setType(p.getType());
        vo.setName(p.getName());
        vo.setDescription(p.getDescription());
        vo.setStatus(p.getStatus());
        vo.setCreatedAt(p.getCreatedAt());
        vo.setUpdatedAt(p.getUpdatedAt());
        // M11 Phase 3：算当前用户在该项目里的权限
        if (userId != null && p.getFamilyId() != null) {
            FamilyMember m = familyMemberMapper.selectOne(
                new LambdaQueryWrapper<FamilyMember>()
                    .eq(FamilyMember::getFamilyId, p.getFamilyId())
                    .eq(FamilyMember::getUserId, userId)
            );
            if (m != null) {
                vo.setMyPermission(m.getRole());
            }
        }
        return vo;
    }
}
