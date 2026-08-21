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
import com.momentweaver.memory.dto.SubjectTreeNodeVO;
import com.momentweaver.memory.dto.SubjectTreeResponse;
import com.momentweaver.memory.dto.SubjectUpdateRequest;
import com.momentweaver.memory.dto.SubjectVO;
import com.momentweaver.memory.entity.Authorization;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.AuthorizationMapper;
import com.momentweaver.memory.mapper.SubjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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

            // M14+ 家族关系图：从 FamilyMember 继承 generation / parentSubjectId
            // 用户在 Subject 创建表单填的 genealogy 字段被忽略（FamilyMember 是 source of truth）
            s.setGeneration(fm.getGeneration());
            s.setParentRelationType(fm.getParentMemberRelationType());
            if (fm.getParentFamilyMemberId() != null) {
                // 把"父 FamilyMember"映射到"父 Subject"——找同项目下 familyMemberId=父 FamilyMember.id 的 Subject
                Subject parentSub = subjectMapper.selectOne(
                    new LambdaQueryWrapper<Subject>()
                        .eq(Subject::getProjectId, projectId)
                        .eq(Subject::getFamilyMemberId, fm.getParentFamilyMemberId())
                );
                if (parentSub != null) {
                    s.setParentSubjectId(parentSub.getId());
                }
                // 如果父 FamilyMember 在本项目还没被加为 Subject，parentSubjectId 留空（合法状态：断链）
            }
        } else {
            // 路径 2：纯匿名被采访者（老流程 / 一次性 token 授权）
            // displayName 必填校验由 DTO 的 @AssertTrue 完成，这里不需要重复
            s.setDisplayName(req.getDisplayName().trim());
            s.setRelation(req.getRelation());
        }
        s.setNote(req.getNote());

        // M14+ 家族关系图：
        //   路径 1（familyMemberId 非空）→ 已在上面从 FamilyMember 继承 generation / parentSubjectId
        //   路径 2（匿名）→ 用户手填 genealogy 字段（applyGenealogyOnCreate）
        if (req.getFamilyMemberId() == null) {
            applyGenealogyOnCreate(s, req);
        }

        subjectMapper.insert(s);

        // M14+ 家族关系图：反向回填"先子后父"创建顺序下的断链子节点
        // 场景：用户先加杨修（父 yang 还未在本项目），后加 yang——yang 进来后应自动把杨修的 parent_subject_id 接上
        // 仅回填绑定 FamilyMember 且 parent_subject_id 仍为 NULL 的 Subject（同 project 内）
        if (s.getFamilyMemberId() != null) {
            List<Subject> boundOrphans = subjectMapper.selectList(
                new LambdaQueryWrapper<Subject>()
                    .eq(Subject::getProjectId, projectId)
                    .isNotNull(Subject::getFamilyMemberId)
                    .isNull(Subject::getParentSubjectId)
            );
            if (!boundOrphans.isEmpty()) {
                java.util.Set<Long> fmIds = boundOrphans.stream()
                    .map(Subject::getFamilyMemberId)
                    .collect(java.util.stream.Collectors.toSet());
                java.util.Map<Long, Long> fmToParentFm = familyMemberMapper.selectBatchIds(fmIds).stream()
                    .filter(fm -> fm.getParentFamilyMemberId() != null)
                    .collect(java.util.stream.Collectors.toMap(
                        FamilyMember::getId, FamilyMember::getParentFamilyMemberId));
                for (Subject orphan : boundOrphans) {
                    Long parentFmId = fmToParentFm.get(orphan.getFamilyMemberId());
                    if (parentFmId != null && parentFmId.equals(s.getFamilyMemberId())) {
                        orphan.setParentSubjectId(s.getId());
                        orphan.setUpdatedAt(now);
                        subjectMapper.updateById(orphan);
                        log.info("subject.create.backfill: orphan subjectId={} familyMemberId={} parentSubjectId={}",
                            orphan.getId(), orphan.getFamilyMemberId(), s.getId());
                    }
                }
            }
        }

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

        // M14+ 家族关系图：处理 generation / parentSubjectId / parentRelationType 更新
        // 哨兵约定：generation=-50 清空；parentSubjectId=-1L 清空；空串清空 relation_type
        // 若 Subject 已绑定 FamilyMember（source of truth），用户改 genealogy 字段被忽略（FamilyMember 优先）；
        // 仅清空哨兵值生效（用于"取消绑定 FamilyMember 后转匿名路径"场景）
        if (s.getFamilyMemberId() == null) {
            applyGenealogyOnUpdate(s, req);
        } else {
            // 绑定 FamilyMember 的 Subject：只接受"清空"语义（强制断开缓存）
            // 用户想修改代际 → 应改 FamilyMember
            if (req.getGeneration() != null && req.getGeneration() != -50) {
                // 静默忽略，不报错（用户改家族成员的 genealogy 才是正确路径）
                log.debug("subject.update: ignore generation={} (subject is bound to family_member)", req.getGeneration());
            }
            if (req.getParentSubjectId() != null && req.getParentSubjectId() != -1L) {
                log.debug("subject.update: ignore parentSubjectId={} (subject is bound to family_member)", req.getParentSubjectId());
            }
            if (req.getParentRelationType() != null && !req.getParentRelationType().isEmpty()) {
                log.debug("subject.update: ignore parentRelationType={} (subject is bound to family_member)", req.getParentRelationType());
            }
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

    /**
     * M14+ 家族关系图：项目级 tree 聚合。
     *
     * <p>返回扁平 nodes（前端 d3.stratify 自建树）+ 待归位 orphans + generation 警告 ids。
     * 故意不返回嵌套结构——换可视化库不用改后端。</p>
     *
     * <p>权限：项目成员即可（与 list 对齐）。</p>
     */
    public SubjectTreeResponse listTree(Long userId, Long projectId) {
        Project p = mustProject(projectId);
        projectAccessChecker.requireMember(projectId, userId);

        List<Subject> subjects = subjectMapper.selectList(
            new LambdaQueryWrapper<Subject>()
                .eq(Subject::getProjectId, projectId)
                .orderByAsc(Subject::getGeneration)  // 排序让孤儿区节点稳定
        );
        if (subjects.isEmpty()) {
            SubjectTreeResponse empty = new SubjectTreeResponse();
            empty.setNodes(Collections.emptyList());
            empty.setOrphans(Collections.emptyList());
            empty.setWarnings(Collections.emptyList());
            empty.setTotal(0);
            return empty;
        }

        // 一次拉所有 subject（用于校验 parentSubjectId 存在性 + 计算 generationWarning）
        Map<Long, Subject> subjMap = subjects.stream()
            .collect(Collectors.toMap(Subject::getId, s -> s));

        List<SubjectTreeNodeVO> nodes = new ArrayList<>(subjects.size());
        List<Long> orphans = new ArrayList<>();
        List<Long> warnings = new ArrayList<>();
        for (Subject s : subjects) {
            SubjectTreeNodeVO n = new SubjectTreeNodeVO();
            n.setId(String.valueOf(s.getId()));
            n.setDisplayName(s.getDisplayName());
            n.setRelation(s.getRelation());
            n.setGeneration(s.getGeneration());
            n.setParentSubjectId(s.getParentSubjectId() == null ? null : String.valueOf(s.getParentSubjectId()));
            n.setParentRelationType(s.getParentRelationType());
            n.setFamilyMemberId(s.getFamilyMemberId() == null ? null : String.valueOf(s.getFamilyMemberId()));

            // 待归位判定：generation=null 或 parent 指向不存在节点（数据完整性兜底）
            boolean orphan = s.getGeneration() == null;
            if (!orphan && s.getParentSubjectId() != null
                && !subjMap.containsKey(s.getParentSubjectId())) {
                orphan = true;
            }
            if (orphan) {
                orphans.add(s.getId());
            }

            // generation 一致性警告：正向语义（正数=长辈、负数=晚辈）下"子代数"="父代数"+1
            //   例：父=1（父母辈）、子=2（儿女辈） → 2 == 1 + 1 ✓
            //   例：父=0（本人辈）、子=1（晚辈） → 1 == 0 + 1 ✓
            // 仅当父存在且双方都填了 generation 时校验
            if (s.getParentSubjectId() != null && s.getGeneration() != null) {
                Subject parent = subjMap.get(s.getParentSubjectId());
                if (parent != null && parent.getGeneration() != null
                    && !s.getGeneration().equals(parent.getGeneration() + 1)) {
                    n.setGenerationWarning(
                        String.format("父 %s 是第 %d 代，本人是第 %d 代，差 %d",
                            parent.getDisplayName(),
                            parent.getGeneration(),
                            s.getGeneration(),
                            Math.abs(s.getGeneration() - (parent.getGeneration() + 1))));
                    warnings.add(s.getId());
                }
            }

            nodes.add(n);
        }

        SubjectTreeResponse resp = new SubjectTreeResponse();
        resp.setNodes(nodes);
        resp.setOrphans(orphans);
        resp.setWarnings(warnings);
        resp.setTotal(nodes.size());
        return resp;
    }

    /**
     * M14+ 家族关系图：家族级聚合树（跨项目）。
     *
     * <p>去重口径：按 {@code family_member_id} 跨项目合并同一家族成员。
     *   <ul>
     *     <li>有 family_member_id 的 Subject：合并为一个节点，id 用 "fm-{familyMemberId}"；
     *         generation / parentSubjectId 取首次出现的值；projectIds 收集所有来源</li>
     *     <li>匿名的 Subject（family_member_id=null）：各项目独立成节点</li>
     *   </ul>
     * </p>
     *
     * <p>权限校验由 FamilyTreeController 完成（familyAccessChecker.requireMember）。
     * 本方法不再二次校验。</p>
     */
    public SubjectTreeResponse listFamilyTree(Long userId, Long familyId) {
        List<Subject> allSubjects = subjectMapper.selectByFamilyId(familyId);
        if (allSubjects.isEmpty()) {
            SubjectTreeResponse empty = new SubjectTreeResponse();
            empty.setNodes(Collections.emptyList());
            empty.setOrphans(Collections.emptyList());
            empty.setWarnings(Collections.emptyList());
            empty.setTotal(0);
            return empty;
        }

        // 去重：按 family_member_id 分组；有 fmId 的合并，无 fmId 的独立
        // 用 LinkedHashMap 保持插入顺序（按 SQL 排序结果）
        java.util.Map<String, List<Subject>> grouped = new java.util.LinkedHashMap<>();
        for (Subject s : allSubjects) {
            String key = s.getFamilyMemberId() != null
                ? "fm-" + s.getFamilyMemberId()
                : "sub-" + s.getId();
            grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(s);
        }

        // 收集所有 Subject.id 用于 parent_subject_id 重映射
        java.util.Set<Long> allSubIds = allSubjects.stream()
            .map(Subject::getId).collect(java.util.stream.Collectors.toSet());
        // subId → nodeKey 映射：把"父 Subject.id"翻译成"父节点对应的树 key（fm-X / sub-X）"
        // 否则前端用 nodeKey 而父写的是 Subject.id，永远找不到 → 全部当孤儿
        java.util.Map<Long, String> subIdToNodeKey = new java.util.HashMap<>();
        for (Subject s : allSubjects) {
            String key = s.getFamilyMemberId() != null
                ? "fm-" + s.getFamilyMemberId()
                : "sub-" + s.getId();
            subIdToNodeKey.putIfAbsent(s.getId(), key);
        }

        // 构造节点
        List<SubjectTreeNodeVO> nodes = new java.util.ArrayList<>();
        List<Long> orphans = new java.util.ArrayList<>();
        List<Long> warnings = new java.util.ArrayList<>();
        java.util.Map<String, Long> nodeKeyToSubId = new java.util.HashMap<>();  // 树节点 key → 主 Subject.id

        for (java.util.Map.Entry<String, List<Subject>> entry : grouped.entrySet()) {
            String nodeKey = entry.getKey();
            List<Subject> group = entry.getValue();
            Subject first = group.get(0);
            // 合并字段：generation / parentSubjectId / parentRelationType 取第一个非 null
            Integer mergedGeneration = null;
            Long mergedParentId = null;
            String mergedParentRelType = null;
            for (Subject s : group) {
                if (mergedGeneration == null && s.getGeneration() != null) {
                    mergedGeneration = s.getGeneration();
                }
                if (mergedParentId == null && s.getParentSubjectId() != null) {
                    mergedParentId = s.getParentSubjectId();
                    mergedParentRelType = s.getParentRelationType();
                }
            }

            SubjectTreeNodeVO n = new SubjectTreeNodeVO();
            // 节点 id：用 nodeKey（fm-X 或 sub-X）作为树节点的稳定标识
            n.setId(nodeKey);
            n.setDisplayName(first.getDisplayName());
            n.setRelation(first.getRelation());
            n.setGeneration(mergedGeneration);
            n.setFamilyMemberId(first.getFamilyMemberId() == null ? null : String.valueOf(first.getFamilyMemberId()));
            // parentSubjectId 重映射：必须从 Subject.id 翻译为父节点对应的 nodeKey（fm-X / sub-X）
            // 不翻译的话前端用 nodeKey 找不到父 → 全部当孤儿 → 树断线
            String remappedParentKey = mergedParentId != null ? subIdToNodeKey.get(mergedParentId) : null;
            n.setParentSubjectId(remappedParentKey);
            n.setParentRelationType(mergedParentRelType);
            // 派生 generationWarning：跨项目合并后若 generation 与父不一致
            // 正向语义（正数=长辈、负数=晚辈）：子比父大 1 代，即子 = 父 + 1
            if (mergedParentId != null && mergedGeneration != null) {
                // lambda 捕获必须用 final 变量；显式赋给 final 局部变量避免遮蔽冲突
                final Long parentIdToFind = mergedParentId;
                final Integer genToCheck = mergedGeneration;
                Subject parent = allSubjects.stream()
                    .filter(s -> parentIdToFind.equals(s.getId()))
                    .findFirst().orElse(null);
                if (parent != null && parent.getGeneration() != null
                    && !genToCheck.equals(parent.getGeneration() + 1)) {
                    n.setGenerationWarning(
                        String.format("父 %s 是第 %d 代，本人是第 %d 代，差 %d",
                            parent.getDisplayName(),
                            parent.getGeneration(),
                            genToCheck,
                            Math.abs(genToCheck - (parent.getGeneration() + 1))));
                    warnings.add(first.getId());
                }
            }
            // 待归位判定
            boolean orphan = mergedGeneration == null
                || (mergedParentId != null && !allSubIds.contains(mergedParentId));
            if (orphan) {
                orphans.add(first.getId());
            }

            nodes.add(n);
            nodeKeyToSubId.put(nodeKey, first.getId());
        }

        SubjectTreeResponse resp = new SubjectTreeResponse();
        resp.setNodes(nodes);
        resp.setOrphans(orphans);
        resp.setWarnings(warnings);
        resp.setTotal(nodes.size());
        return resp;
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

        // M14+ 家族关系图：派生字段
        vo.setGeneration(s.getGeneration());
        vo.setParentSubjectId(s.getParentSubjectId());
        vo.setParentRelationType(s.getParentRelationType());

        // 派生 parentDisplayName + generationWarning（需要单条查询父节点）
        if (s.getParentSubjectId() != null) {
            Subject parent = subjectMapper.selectById(s.getParentSubjectId());
            if (parent != null) {
                vo.setParentDisplayName(parent.getDisplayName());
                // generation 一致性警告：反向语义（正数=长辈、负数=晚辈）下父应 = 子 + 1
                if (s.getGeneration() != null && parent.getGeneration() != null
                    && !s.getGeneration().equals(parent.getGeneration() - 1)) {
                    vo.setGenerationWarning(
                        String.format("父 %s 是第 %d 代，本人是第 %d 代，差 %d",
                            parent.getDisplayName(),
                            parent.getGeneration(),
                            s.getGeneration(),
                            Math.abs(s.getGeneration() - (parent.getGeneration() - 1))));
                }
            }
        }

        return vo;
    }

    // ============ M14+ 家族关系图：字段落地与校验 ============

    /**
     * 创建场景：把请求里的 genealogy 字段写到 Subject 上，调用 validateGenealogy 校验。
     *
     * <p>create 时 Subject.id 还未生成，但校验逻辑只看 parentSubjectId 是否指向同项目的
     * 已存在节点，不依赖自身 id（自环检测仅在 update 路径检查 parent==self）。</p>
     */
    private void applyGenealogyOnCreate(Subject s, SubjectCreateRequest req) {
        s.setGeneration(req.getGeneration());
        s.setParentSubjectId(req.getParentSubjectId());
        s.setParentRelationType(req.getParentRelationType());
        if (req.getParentSubjectId() != null) {
            validateGenealogy(s.getProjectId(), req.getParentSubjectId(), null);
        }
    }

    /**
     * 更新场景：处理哨兵值，写字段并校验。
     *
     * <p>哨兵约定：
     * <ul>
     *   <li>{@code generation = -50} → 清空（置 NULL）；其他值（含 null=不变）按字面写</li>
     *   <li>{@code parentSubjectId = -1L} → 清空（置 NULL）；其他值按字面写</li>
     *   <li>{@code parentRelationType = ""} → 清空（置 NULL）；null=不变</li>
     * </ul>
     */
    private void applyGenealogyOnUpdate(Subject s, SubjectUpdateRequest req) {
        Long newParentId = s.getParentSubjectId();
        boolean parentChanged = false;
        if (req.getGeneration() != null) {
            if (req.getGeneration() == -50) {
                s.setGeneration(null);
            } else {
                s.setGeneration(req.getGeneration());
            }
        }
        if (req.getParentSubjectId() != null) {
            if (req.getParentSubjectId() == -1L) {
                newParentId = null;
                s.setParentSubjectId(null);
                parentChanged = true;
            } else {
                newParentId = req.getParentSubjectId();
                s.setParentSubjectId(req.getParentSubjectId());
                parentChanged = true;
            }
        }
        if (req.getParentRelationType() != null) {
            s.setParentRelationType(req.getParentRelationType().isEmpty() ? null : req.getParentRelationType());
        }
        // 父被设置或改变时才校验
        if (parentChanged && newParentId != null) {
            validateGenealogy(s.getProjectId(), newParentId, s.getId());
        }
    }

    /**
     * 家族关系图校验：父节点存在性 + 同 project_id + 自环 + 环检测。
     *
     * <p>generation 一致性 <b>不</b>抛错——只在前端 VO 里以 generationWarning 标记。
     * 用户录入天然乱序增量，硬校验会让人放弃录入。</p>
     *
     * @param projectId   当前 subject 所在项目
     * @param parentId    父 subject id（必须非 null）
     * @param selfId      当前 subject id（create 路径下为 null；update 路径下用于自环检测）
     * @throws BusinessException 父不存在 / 跨项目 / 自环 / 成环
     */
    private void validateGenealogy(Long projectId, Long parentId, Long selfId) {
        // 1) 父存在
        Subject parent = subjectMapper.selectById(parentId);
        if (parent == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "父节点不存在");
        }
        // 2) 父必须在同项目
        if (!parent.getProjectId().equals(projectId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "父节点必须属于同一项目");
        }
        // 3) 自环：自己的父不能是自己
        if (selfId != null && parentId.equals(selfId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "父节点不能是自己");
        }
        // 4) 环检测：向上遍历 parent 链（上限 100 层）—— 防两个 subject 互为父子
        //    老数据（V17 前）字段不存在，理论上不可能有环，但兜底扫描避免脏数据
        Long cursor = parent.getParentSubjectId();
        int hops = 0;
        while (cursor != null && hops < 100) {
            if (cursor.equals(parentId) || (selfId != null && cursor.equals(selfId))) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "父节点链中存在环，请先修正上游节点的父");
            }
            Subject next = subjectMapper.selectById(cursor);
            if (next == null) break;
            cursor = next.getParentSubjectId();
            hops++;
        }
        if (hops >= 100) {
            // 理论不会触发（环检测已 throw），但兜底防止极端深链拖死请求
            throw new BusinessException(ResultCode.BAD_REQUEST, "父节点链过深（>100 层），疑似数据异常");
        }
    }
}
