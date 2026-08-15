package com.momentweaver.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.security.ProjectAccessChecker;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.memory.dto.SubjectCreateRequest;
import com.momentweaver.memory.dto.SubjectUpdateRequest;
import com.momentweaver.memory.dto.SubjectVO;
import com.momentweaver.memory.entity.Authorization;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.AuthorizationMapper;
import com.momentweaver.memory.mapper.SubjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubjectService {

    private final SubjectMapper subjectMapper;
    private final AuthorizationMapper authorizationMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessChecker projectAccessChecker;

    @Transactional
    public SubjectVO create(Long userId, Long projectId, SubjectCreateRequest req) {
        Project p = mustProject(projectId);
        projectAccessChecker.requireEditor(projectId, userId);

        Subject s = new Subject();
        s.setProjectId(projectId);
        s.setDisplayName(req.getDisplayName().trim());
        s.setRelation(req.getRelation());
        s.setNote(req.getNote());
        s.setHasAccount(0);
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        subjectMapper.insert(s);
        return toVO(s, null);
    }

    public List<SubjectVO> list(Long userId, Long projectId) {
        Project p = mustProject(projectId);
        projectAccessChecker.requireMember(projectId, userId);

        List<Subject> subjects = subjectMapper.selectList(
            new LambdaQueryWrapper<Subject>()
                .eq(Subject::getProjectId, projectId)
                .orderByDesc(Subject::getUpdatedAt)
        );
        return subjects.stream()
            .map(s -> {
                Authorization latest = authorizationMapper.selectOne(
                    new LambdaQueryWrapper<Authorization>()
                        .eq(Authorization::getSubjectId, s.getId())
                        .orderByDesc(Authorization::getCreatedAt)
                        .last("LIMIT 1")
                );
                return toVO(s, latest);
            })
            .toList();
    }

    public SubjectVO get(Long userId, Long subjectId) {
        Subject s = mustSubject(subjectId);
        projectAccessChecker.requireMember(s.getProjectId(), userId);
        Authorization latest = authorizationMapper.selectOne(
            new LambdaQueryWrapper<Authorization>()
                .eq(Authorization::getSubjectId, subjectId)
                .orderByDesc(Authorization::getCreatedAt)
                .last("LIMIT 1")
        );
        return toVO(s, latest);
    }

    /**
     * 局部更新人物。只有请求里出现的字段会被改动；relation/note 显式传 "" 视为清空。
     * 权限：editor 即可（与 create 对齐）。注意：note 字段虽然设计上仅 owner 可见，
     * 但当前 list/get 都会返回给所有成员，权限收紧放在 M3 再做。
     */
    @Transactional
    public SubjectVO update(Long userId, Long subjectId, SubjectUpdateRequest req) {
        Subject s = mustSubject(subjectId);
        projectAccessChecker.requireEditor(s.getProjectId(), userId);

        if (req.getDisplayName() != null) {
            s.setDisplayName(req.getDisplayName().trim());
        }
        if (req.getRelation() != null) {
            // 显式传 "" 清空；不传保持原值
            s.setRelation(req.getRelation().isEmpty() ? null : req.getRelation().trim());
        }
        if (req.getNote() != null) {
            s.setNote(req.getNote().isEmpty() ? null : req.getNote().trim());
        }
        s.setUpdatedAt(LocalDateTime.now());
        subjectMapper.updateById(s);

        Authorization latest = authorizationMapper.selectOne(
            new LambdaQueryWrapper<Authorization>()
                .eq(Authorization::getSubjectId, subjectId)
                .orderByDesc(Authorization::getCreatedAt)
                .last("LIMIT 1")
        );
        return toVO(s, latest);
    }

    @Transactional
    public void delete(Long userId, Long subjectId) {
        Subject s = mustSubject(subjectId);
        // 删除人物需要项目 owner 权限（家族项目 = family admin）
        projectAccessChecker.requireOwner(s.getProjectId(), userId);
        subjectMapper.deleteById(subjectId);
    }

    // ---- helpers ----

    private Project mustProject(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        return p;
    }

    private Subject mustSubject(Long subjectId) {
        Subject s = subjectMapper.selectById(subjectId);
        if (s == null) throw new BusinessException(ResultCode.SUBJECT_NOT_FOUND);
        return s;
    }

    private SubjectVO toVO(Subject s, Authorization latest) {
        SubjectVO vo = new SubjectVO();
        vo.setId(s.getId());
        vo.setProjectId(s.getProjectId());
        vo.setDisplayName(s.getDisplayName());
        vo.setRelation(s.getRelation());
        vo.setHasAccount(s.getHasAccount());
        vo.setLinkedUserId(s.getLinkedUserId());
        vo.setNote(s.getNote());
        if (latest != null) {
            vo.setLatestAuthId(latest.getId());
            vo.setLatestAuthStatus(latest.getStatus());
        }
        vo.setCreatedAt(s.getCreatedAt());
        vo.setUpdatedAt(s.getUpdatedAt());
        return vo;
    }
}
