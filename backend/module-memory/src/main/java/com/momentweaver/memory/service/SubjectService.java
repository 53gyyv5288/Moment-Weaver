package com.momentweaver.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.FamilyMember;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.User;
import com.momentweaver.account.mapper.FamilyMemberMapper;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.UserMapper;
import com.momentweaver.account.security.ProjectAccessChecker;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.memory.dto.EligibleFamilyMemberVO;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubjectService {

    private final SubjectMapper subjectMapper;
    private final AuthorizationMapper authorizationMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessChecker projectAccessChecker;
    /** M11 Phase 2：用于从家族成员里选被采访者 */
    private final FamilyMemberMapper familyMemberMapper;
    /** M11 Phase 2：用于读家族成员的 displayName/avatarUrl */
    private final UserMapper userMapper;

    @Transactional
    public SubjectVO create(Long userId, Long projectId, SubjectCreateRequest req) {
        Project p = mustProject(projectId);
        projectAccessChecker.requireEditor(projectId, userId);

        Subject s = new Subject();
        s.setProjectId(projectId);
        s.setHasAccount(0);
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);

        // M11 Phase 2：分支：家族成员 vs 匿名
        if (req.getFamilyMemberId() != null) {
            // 路径 1：从家族成员里选
            FamilyMember fm = familyMemberMapper.selectById(req.getFamilyMemberId());
            if (fm == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "家族成员不存在");
            }
            // 必须属于项目所属家族（个人项目不支持这种关联）
            if (p.getFamilyId() == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                    "个人项目不支持关联家族成员，请用「匿名」模式添加");
            }
            if (!fm.getFamilyId().equals(p.getFamilyId())) {
                throw new BusinessException(ResultCode.FORBIDDEN,
                    "该成员不属于本项目所属家族");
            }
            // M11 Phase 3：开放"自采访"（自传场景）
            // 之前禁止把自己添加为被采访者（避免误操作），但个人传记场景需要
            // 现在只在前端用下拉框的"我自己"标识区分，不在 service 强制禁止
            // 一个家族成员在同一项目下只能被添加一次
            Long dup = subjectMapper.selectCount(
                new LambdaQueryWrapper<Subject>()
                    .eq(Subject::getProjectId, projectId)
                    .eq(Subject::getFamilyMemberId, fm.getId())
            );
            if (dup != null && dup > 0) {
                throw new BusinessException(ResultCode.CONFLICT,
                    "该家族成员已被添加为本项目被采访者");
            }
            s.setFamilyMemberId(fm.getId());
            s.setLinkedUserId(fm.getUserId());  // 同步：被采访者本身的 userId
            // 从 user 表拿真实姓名（家族成员可能没账号 → 回退）
            if (fm.getUserId() != null) {
                User u = userMapper.selectById(fm.getUserId());
                if (u != null && u.getDisplayName() != null && !u.getDisplayName().isBlank()) {
                    s.setDisplayName(u.getDisplayName());
                    s.setHasAccount(1);
                } else {
                    // 家族成员关联了 userId 但 user 不存在 / 没名字（边界情况）
                    s.setDisplayName("家人-" + fm.getId());
                    s.setHasAccount(0);
                }
            } else {
                // 极少见：family_member 存了 userId=null 的孤儿记录
                s.setDisplayName("家人-" + fm.getId());
                s.setHasAccount(0);
            }
            // relation 由用户继续填（不强覆盖）
            s.setRelation(req.getRelation());
        } else {
            // 路径 2：纯匿名被采访者（老流程 / 一次性 token 授权）
            // displayName 必填校验由 DTO 的 @AssertTrue 完成，这里不需要重复
            s.setDisplayName(req.getDisplayName().trim());
            s.setRelation(req.getRelation());
        }
        s.setNote(req.getNote());

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
        if (subjects.isEmpty()) return Collections.emptyList();

        // M11 Phase 2：批量取家族成员 + user 信息
        Set<Long> fmIds = subjects.stream()
            .map(Subject::getFamilyMemberId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, FamilyMember> fmMap = fmIds.isEmpty() ? Collections.emptyMap()
            : familyMemberMapper.selectBatchIds(fmIds).stream()
                .collect(Collectors.toMap(FamilyMember::getId, fm -> fm));

        Set<Long> userIds = fmMap.values().stream()
            .map(FamilyMember::getUserId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
            : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // 批量取每个 subject 的最新授权（IN 查询）
        List<Long> subjectIds = subjects.stream().map(Subject::getId).toList();
        Map<Long, Authorization> latestAuthMap = new HashMap<>();
        for (Long sid : subjectIds) {
            Authorization latest = authorizationMapper.selectOne(
                new LambdaQueryWrapper<Authorization>()
                    .eq(Authorization::getSubjectId, sid)
                    .orderByDesc(Authorization::getCreatedAt)
                    .last("LIMIT 1")
            );
            if (latest != null) latestAuthMap.put(sid, latest);
        }

        return subjects.stream()
            .map(s -> toVO(s, latestAuthMap.get(s.getId()), fmMap, userMap))
            .toList();
    }

    public SubjectVO get(Long userId, Long subjectId) {
        Subject s = mustSubject(subjectId);
        projectAccessChecker.requireMember(s.getProjectId(), userId);

        Map<Long, FamilyMember> fmMap = Collections.emptyMap();
        Map<Long, User> userMap = Collections.emptyMap();
        if (s.getFamilyMemberId() != null) {
            FamilyMember fm = familyMemberMapper.selectById(s.getFamilyMemberId());
            if (fm != null) {
                fmMap = Map.of(fm.getId(), fm);
                if (fm.getUserId() != null) {
                    User u = userMapper.selectById(fm.getUserId());
                    if (u != null) userMap = Map.of(u.getId(), u);
                }
            }
        }

        Authorization latest = authorizationMapper.selectOne(
            new LambdaQueryWrapper<Authorization>()
                .eq(Authorization::getSubjectId, subjectId)
                .orderByDesc(Authorization::getCreatedAt)
                .last("LIMIT 1")
        );
        return toVO(s, latest, fmMap, userMap);
    }

    /**
     * M11 Phase 2：列出项目下"可选的被采访者"——从家族成员里筛掉"已添加为本项目被采访者"的。
     * 仅家族项目有结果（个人项目返回空列表）。
     */
    public List<EligibleFamilyMemberVO> listEligibleFamilyMembers(Long userId, Long projectId) {
        Project p = mustProject(projectId);
        projectAccessChecker.requireMember(projectId, userId);

        if (p.getFamilyId() == null) {
            // 个人项目没有家族成员可选
            return Collections.emptyList();
        }

        // 1) 家族所有成员
        List<FamilyMember> allMembers = familyMemberMapper.selectList(
            new LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getFamilyId, p.getFamilyId())
                .orderByAsc(FamilyMember::getJoinedAt)
        );
        if (allMembers.isEmpty()) return Collections.emptyList();

        // 2) 已经在本项目下被添加的家族成员（去重）
        Set<Long> alreadySubjectFmIds = new HashSet<>();
        List<Subject> existing = subjectMapper.selectList(
            new LambdaQueryWrapper<Subject>()
                .eq(Subject::getProjectId, projectId)
                .isNotNull(Subject::getFamilyMemberId)
        );
        Map<Long, Long> fmToSubject = new HashMap<>();
        for (Subject s : existing) {
            alreadySubjectFmIds.add(s.getFamilyMemberId());
            fmToSubject.put(s.getFamilyMemberId(), s.getId());
        }

        // 3) 批量取 user 信息
        Set<Long> userIds = allMembers.stream()
            .map(FamilyMember::getUserId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
            : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<EligibleFamilyMemberVO> result = new ArrayList<>(allMembers.size());
        for (FamilyMember fm : allMembers) {
            User u = fm.getUserId() != null ? userMap.get(fm.getUserId()) : null;
            EligibleFamilyMemberVO vo = new EligibleFamilyMemberVO();
            vo.setFamilyMemberId(fm.getId());
            vo.setUserId(fm.getUserId());
            vo.setDisplayName(u != null ? u.getDisplayName() : ("family-member-" + fm.getId()));
            vo.setRole(fm.getRole());
            vo.setPhone(u != null ? u.getPhone() : null);
            vo.setEmail(u != null ? u.getEmail() : null);
            vo.setAvatarUrl(u != null ? u.getAvatarUrl() : null);
            boolean already = alreadySubjectFmIds.contains(fm.getId());
            vo.setHasSubject(already);
            if (already) {
                vo.setExistingSubjectId(fmToSubject.get(fm.getId()));
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 局部更新人物。只有请求里出现的字段会被改动；relation/note 显式传 "" 视为清空。
     * 权限：editor 即可（与 create 对齐）。注意：note 字段虽然设计上仅 owner 可见，
     * 但当前 list/get 都会返回给所有成员，权限收紧放在 M3 再做。
     *
     * <p>M11 Phase 2：禁止通过 update 改变 family_member_id（创建后即锁定）。
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

        Map<Long, FamilyMember> fmMap = Collections.emptyMap();
        Map<Long, User> userMap = Collections.emptyMap();
        if (s.getFamilyMemberId() != null) {
            FamilyMember fm = familyMemberMapper.selectById(s.getFamilyMemberId());
            if (fm != null) {
                fmMap = Map.of(fm.getId(), fm);
                if (fm.getUserId() != null) {
                    User u = userMapper.selectById(fm.getUserId());
                    if (u != null) userMap = Map.of(u.getId(), u);
                }
            }
        }
        Authorization latest = authorizationMapper.selectOne(
            new LambdaQueryWrapper<Authorization>()
                .eq(Authorization::getSubjectId, subjectId)
                .orderByDesc(Authorization::getCreatedAt)
                .last("LIMIT 1")
        );
        return toVO(s, latest, fmMap, userMap);
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
        return toVO(s, latest, Collections.emptyMap(), Collections.emptyMap());
    }

    private SubjectVO toVO(Subject s, Authorization latest,
                           Map<Long, FamilyMember> fmMap, Map<Long, User> userMap) {
        SubjectVO vo = new SubjectVO();
        vo.setId(s.getId());
        vo.setProjectId(s.getProjectId());
        vo.setDisplayName(s.getDisplayName());
        vo.setRelation(s.getRelation());
        vo.setHasAccount(s.getHasAccount());
        vo.setLinkedUserId(s.getLinkedUserId());
        vo.setFamilyMemberId(s.getFamilyMemberId());
        // 派生：家族成员的展示名和头像（前端"采访家人"标签用）
        if (s.getFamilyMemberId() != null) {
            FamilyMember fm = fmMap.get(s.getFamilyMemberId());
            if (fm != null && fm.getUserId() != null) {
                User u = userMap.get(fm.getUserId());
                if (u != null) {
                    vo.setFamilyMemberDisplayName(u.getDisplayName());
                    vo.setFamilyMemberAvatarUrl(u.getAvatarUrl());
                }
            }
        }
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
