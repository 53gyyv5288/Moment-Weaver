package com.momentweaver.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.dto.AdminCreateUserRequest;
import com.momentweaver.account.dto.AdminCreateUserResponse;
import com.momentweaver.account.dto.CreateFamilyRequest;
import com.momentweaver.account.dto.FamilyMemberVO;
import com.momentweaver.account.dto.FamilyVO;
import com.momentweaver.account.dto.UpdateFamilyMemberRequest;
import com.momentweaver.account.dto.UpdateFamilyRequest;
import com.momentweaver.account.entity.Family;
import com.momentweaver.account.entity.FamilyMember;
import com.momentweaver.account.entity.User;
import com.momentweaver.account.mapper.FamilyMapper;
import com.momentweaver.account.mapper.FamilyMemberMapper;
import com.momentweaver.account.mapper.UserMapper;
import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.account.security.FamilyAccessChecker;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 家族服务（M10+ Family）。
 *
 * <p>覆盖：
 *   <ul>
 *     <li>创建/查询/修改家族</li>
 *     <li>成员增删改（创建成员账号并直接加入家族）</li>
 *     <li>角色变更 / 密码重置</li>
 *   </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyService {

    private static final Set<String> VALID_ROLES = Set.of("admin", "editor", "viewer");

    private final FamilyMapper familyMapper;
    private final FamilyMemberMapper familyMemberMapper;
    private final UserMapper userMapper;
    private final FamilyAccessChecker familyAccessChecker;
    private final PasswordEncoder passwordEncoder;

    // ============ 家族 CRUD ============

    /**
     * 创建家族。副作用：
     *   <ol>
     *     <li>INSERT family</li>
     *     <li>INSERT family_member (creator as admin)</li>
     *     <li>UPDATE user SET is_family_admin = 1 (创建者标记为家族管理员)</li>
     *   </ol>
     */
    @Transactional
    public FamilyVO create(Long creatorUserId, CreateFamilyRequest req) {
        Family f = new Family();
        f.setName(req.getName().trim());
        f.setDescription(req.getDescription());
        f.setOwnerUserId(creatorUserId);
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        familyMapper.insert(f);

        // 创建者自动成为 admin 成员
        FamilyMember me = new FamilyMember();
        me.setFamilyId(f.getId());
        me.setUserId(creatorUserId);
        me.setRole("admin");
        me.setJoinedAt(LocalDateTime.now());
        me.setCreatedAt(LocalDateTime.now());
        me.setUpdatedAt(LocalDateTime.now());
        familyMemberMapper.insert(me);

        // 标记创建者为家族管理员（用于前端显示徽章）
        User u = userMapper.selectById(creatorUserId);
        if (u != null && (u.getIsFamilyAdmin() == null || u.getIsFamilyAdmin() == 0)) {
            u.setIsFamilyAdmin(1);
            u.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(u);
        }

        log.info("family.created: id={} name={} owner={}", f.getId(), f.getName(), creatorUserId);
        return toVO(f, "admin", 1, 0);
    }

    /**
     * 当前用户的家族列表（含 myRole）。
     */
    public List<FamilyVO> listMine(Long userId) {
        List<FamilyMember> memberships = familyMemberMapper.selectList(
            new LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getUserId, userId)
                .orderByDesc(FamilyMember::getJoinedAt)
        );
        if (memberships.isEmpty()) return Collections.emptyList();

        // 一次性查所有 family
        List<Long> familyIds = memberships.stream()
            .map(FamilyMember::getFamilyId).toList();
        Map<Long, Family> familyMap = familyMapper.selectBatchIds(familyIds).stream()
            .collect(Collectors.toMap(Family::getId, f -> f));

        // 每个 family 统计成员数（独立 count 查询，简单起见不做聚合）
        Map<Long, Integer> memberCountMap = new HashMap<>();
        for (Long fid : familyIds) {
            Long c = familyMemberMapper.selectCount(
                new LambdaQueryWrapper<FamilyMember>().eq(FamilyMember::getFamilyId, fid)
            );
            memberCountMap.put(fid, c == null ? 0 : c.intValue());
        }

        return memberships.stream()
            .map(m -> {
                Family f = familyMap.get(m.getFamilyId());
                if (f == null) return null;
                return toVO(f, m.getRole(), memberCountMap.getOrDefault(f.getId(), 0), null);
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    public FamilyVO get(Long userId, Long familyId) {
        Family f = mustGet(familyId);
        String role = familyAccessChecker.requireMember(familyId, userId).getRole();
        Long memberCount = familyMemberMapper.selectCount(
            new LambdaQueryWrapper<FamilyMember>().eq(FamilyMember::getFamilyId, familyId)
        );
        return toVO(f, role, memberCount == null ? 0 : memberCount.intValue(), null);
    }

    @Transactional
    public FamilyVO update(Long userId, Long familyId, UpdateFamilyRequest req) {
        familyAccessChecker.requireAdmin(familyId, userId);
        Family f = mustGet(familyId);
        if (req.getName() != null && !req.getName().isBlank()) {
            f.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            f.setDescription(req.getDescription().isEmpty() ? null : req.getDescription());
        }
        f.setUpdatedAt(LocalDateTime.now());
        familyMapper.updateById(f);

        String role = familyAccessChecker.requireMember(familyId, userId).getRole();
        log.info("family.updated: id={} by user={}", familyId, userId);
        return toVO(f, role, null, null);
    }

    // ============ 成员管理 ============

    public List<FamilyMemberVO> listMembers(Long userId, Long familyId) {
        familyAccessChecker.requireMember(familyId, userId);

        List<FamilyMember> members = familyMemberMapper.selectList(
            new LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getFamilyId, familyId)
                .orderByAsc(FamilyMember::getJoinedAt)
        );
        if (members.isEmpty()) return Collections.emptyList();

        List<Long> userIds = members.stream().map(FamilyMember::getUserId).toList();
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
            .collect(Collectors.toMap(User::getId, u -> u));

        // M14+：批量取父成员的 displayName（userId → displayName 映射）
        List<Long> parentFmIds = members.stream()
            .map(FamilyMember::getParentFamilyMemberId)
            .filter(java.util.Objects::nonNull)
            .toList();
        Map<Long, String> parentDisplayMap = parentFmIds.isEmpty() ? Collections.emptyMap()
            : familyMemberMapper.selectBatchIds(parentFmIds).stream()
                .collect(Collectors.toMap(FamilyMember::getId, fm -> {
                    User pu = userMap.get(fm.getUserId());
                    return pu != null ? pu.getDisplayName() : "家人-" + fm.getId();
                }));

        return members.stream().map(m -> {
            User u = userMap.get(m.getUserId());
            FamilyMemberVO vo = new FamilyMemberVO();
            vo.setId(m.getId());
            vo.setFamilyId(m.getFamilyId());
            vo.setUserId(m.getUserId());
            vo.setRole(m.getRole());
            vo.setJoinedAt(m.getJoinedAt());
            if (u != null) {
                vo.setDisplayName(u.getDisplayName());
                vo.setPhone(u.getPhone());
                vo.setEmail(u.getEmail());
                vo.setAvatarUrl(u.getAvatarUrl());
            }
            // M14+ 家族关系图字段
            vo.setGeneration(m.getGeneration());
            vo.setParentFamilyMemberId(m.getParentFamilyMemberId());
            vo.setParentMemberRelationType(m.getParentMemberRelationType());
            if (m.getParentFamilyMemberId() != null) {
                vo.setParentDisplayName(parentDisplayMap.get(m.getParentFamilyMemberId()));
            }
            return vo;
        }).toList();
    }

    /**
     * 家族管理员创建成员账号并加入家族。
     *
     * <p>副作用：
     *   <ol>
     *     <li>INSERT user (must_change_password=1, created_by_user_id=adminId)</li>
     *     <li>INSERT family_member</li>
     *   </ol>
     *
     * <p>返回响应里带 initialPassword 明文 —— 仅创建时返回一次，管理员需告知。
     */
    @Transactional
    public AdminCreateUserResponse adminCreateUser(Long adminUserId, Long familyId, AdminCreateUserRequest req) {
        familyAccessChecker.requireAdmin(familyId, adminUserId);

        String phone = req.getPhone() == null ? null : req.getPhone().trim();
        String email = req.getEmail() == null ? null : req.getEmail().trim();
        if (phone != null && !phone.isBlank()) {
            Long dup = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getPhone, phone)
            );
            if (dup != null && dup > 0) {
                throw new BusinessException(ResultCode.USER_ALREADY_EXISTS, "手机号已被注册");
            }
        }
        if (email != null && !email.isBlank()) {
            Long dup = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)
            );
            if (dup != null && dup > 0) {
                throw new BusinessException(ResultCode.USER_ALREADY_EXISTS, "邮箱已被注册");
            }
        }

        User u = new User();
        u.setPhone(phone);
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        u.setDisplayName(req.getDisplayName().trim());
        u.setStatus(1);
        u.setIsFamilyAdmin(0); // 仅 owner 拥有此标记；这里新成员不是家族管理员
        u.setMustChangePassword(1);
        u.setCreatedByUserId(adminUserId);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(u);

        FamilyMember m = new FamilyMember();
        m.setFamilyId(familyId);
        m.setUserId(u.getId());
        m.setRole(req.getRole());
        m.setJoinedAt(LocalDateTime.now());
        m.setCreatedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());

        // M14+ 家族关系图：写入代际 + 上一代 + 关系类型，调用校验
        m.setGeneration(req.getGeneration());
        m.setParentFamilyMemberId(req.getParentFamilyMemberId());
        m.setParentMemberRelationType(req.getParentMemberRelationType());
        if (req.getParentFamilyMemberId() != null) {
            validateFamilyGenealogy(familyId, req.getParentFamilyMemberId(), null);
        }

        familyMemberMapper.insert(m);

        log.info("family.member.created: familyId={} userId={} role={} by admin={}",
            familyId, u.getId(), req.getRole(), adminUserId);

        return new AdminCreateUserResponse(
            u.getId(),
            u.getDisplayName(),
            req.getPassword(),
            req.getRole(),
            true
        );
    }

    /**
     * 改成员角色 + 可选重置密码。
     */
    @Transactional
    public FamilyMemberVO updateMember(Long adminUserId, Long familyId, Long memberUserId,
                                       UpdateFamilyMemberRequest req) {
        familyAccessChecker.requireAdmin(familyId, adminUserId);
        FamilyMember m = familyMemberMapper.selectOne(
            new LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getUserId, memberUserId)
        );
        if (m == null) {
            throw new BusinessException(ResultCode.FAMILY_NOT_MEMBER, "该用户不是家族成员");
        }

        // 业务规则：家族至少有 1 个 admin；不能把自己降级
        if (!"admin".equals(req.getRole()) && "admin".equals(m.getRole())) {
            Long adminCount = familyMemberMapper.selectCount(
                new LambdaQueryWrapper<FamilyMember>()
                    .eq(FamilyMember::getFamilyId, familyId)
                    .eq(FamilyMember::getRole, "admin")
            );
            if (adminCount != null && adminCount <= 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "家族至少保留 1 名管理员");
            }
        }

        m.setRole(req.getRole());
        m.setUpdatedAt(LocalDateTime.now());

        // M14+ 家族关系图：处理 genealogy 字段更新（哨兵值约定同 Subject）
        Long newParentId = m.getParentFamilyMemberId();
        boolean parentChanged = false;
        if (req.getGeneration() != null) {
            m.setGeneration(req.getGeneration() == -50 ? null : req.getGeneration());
        }
        if (req.getParentFamilyMemberId() != null) {
            if (req.getParentFamilyMemberId() == -1L) {
                m.setParentFamilyMemberId(null);
                parentChanged = true;
            } else {
                m.setParentFamilyMemberId(req.getParentFamilyMemberId());
                parentChanged = true;
            }
        }
        if (req.getParentMemberRelationType() != null) {
            m.setParentMemberRelationType(
                req.getParentMemberRelationType().isEmpty() ? null : req.getParentMemberRelationType());
        }
        if (parentChanged && m.getParentFamilyMemberId() != null) {
            validateFamilyGenealogy(familyId, m.getParentFamilyMemberId(), m.getId());
        }

        familyMemberMapper.updateById(m);

        if (req.getResetPassword() != null && !req.getResetPassword().isBlank()) {
            User u = userMapper.selectById(memberUserId);
            if (u != null) {
                u.setPasswordHash(passwordEncoder.encode(req.getResetPassword()));
                u.setMustChangePassword(1); // 重置后下次登录强制改密
                u.setUpdatedAt(LocalDateTime.now());
                userMapper.updateById(u);
            }
        }

        User u = userMapper.selectById(memberUserId);
        FamilyMemberVO vo = new FamilyMemberVO();
        vo.setId(m.getId());
        vo.setFamilyId(m.getFamilyId());
        vo.setUserId(memberUserId);
        vo.setRole(m.getRole());
        vo.setJoinedAt(m.getJoinedAt());
        if (u != null) {
            vo.setDisplayName(u.getDisplayName());
            vo.setPhone(u.getPhone());
            vo.setEmail(u.getEmail());
            vo.setAvatarUrl(u.getAvatarUrl());
        }
        // M14+ 家族关系图字段
        vo.setGeneration(m.getGeneration());
        vo.setParentFamilyMemberId(m.getParentFamilyMemberId());
        vo.setParentMemberRelationType(m.getParentMemberRelationType());
        if (m.getParentFamilyMemberId() != null) {
            FamilyMember parentFm = familyMemberMapper.selectById(m.getParentFamilyMemberId());
            if (parentFm != null && parentFm.getUserId() != null) {
                User pu = userMapper.selectById(parentFm.getUserId());
                if (pu != null) {
                    vo.setParentDisplayName(pu.getDisplayName());
                }
            }
        }
        log.info("family.member.updated: familyId={} userId={} newRole={} by admin={}",
            familyId, memberUserId, req.getRole(), adminUserId);
        return vo;
    }

    /**
     * 移除成员。
     *
     * <p>业务规则：家族至少有 1 个 admin；不能移除自己（除非先转让）。
     */
    @Transactional
    public void removeMember(Long adminUserId, Long familyId, Long memberUserId) {
        familyAccessChecker.requireAdmin(familyId, adminUserId);

        FamilyMember m = familyMemberMapper.selectOne(
            new LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getUserId, memberUserId)
        );
        if (m == null) {
            throw new BusinessException(ResultCode.FAMILY_NOT_MEMBER, "该用户不是家族成员");
        }
        if ("admin".equals(m.getRole())) {
            Long adminCount = familyMemberMapper.selectCount(
                new LambdaQueryWrapper<FamilyMember>()
                    .eq(FamilyMember::getFamilyId, familyId)
                    .eq(FamilyMember::getRole, "admin")
            );
            if (adminCount != null && adminCount <= 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "家族至少保留 1 名管理员");
            }
        }

        familyMemberMapper.deleteById(m.getId());
        log.info("family.member.removed: familyId={} userId={} by admin={}",
            familyId, memberUserId, adminUserId);
        // 注意：被移除的 user 账号本身不删除；只是脱离家族
    }

    // ============ helpers ============

    private Family mustGet(Long familyId) {
        Family f = familyMapper.selectById(familyId);
        if (f == null) throw new BusinessException(ResultCode.FAMILY_NOT_FOUND);
        return f;
    }

    /**
     * M14+ 家族关系图校验：父 family_member 必须存在 + 同 family_id + 非自环 + 链上无环。
     *
     * <p>环检测遍历 parent 链上限 100 层 —— 防止两个 family_member 互为父子。
     * generation 一致性 <b>不</b>抛错——只在前端展示，由用户后续修正。</p>
     *
     * @param familyId       当前 family_member 所在家族
     * @param parentFmId     父 family_member.id（必须非 null）
     * @param selfFmId       当前 family_member.id（create 路径下为 null）
     */
    private void validateFamilyGenealogy(Long familyId, Long parentFmId, Long selfFmId) {
        FamilyMember parent = familyMemberMapper.selectById(parentFmId);
        if (parent == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上一代家族成员不存在");
        }
        if (!parent.getFamilyId().equals(familyId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上一代必须属于同一家族");
        }
        if (selfFmId != null && parentFmId.equals(selfFmId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上一代不能是自己");
        }
        // 环检测：向上遍历 parent 链（上限 100 层）
        Long cursor = parent.getParentFamilyMemberId();
        int hops = 0;
        while (cursor != null && hops < 100) {
            if (cursor.equals(parentFmId) || (selfFmId != null && cursor.equals(selfFmId))) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "上一代链中存在环，请先修正上游节点");
            }
            FamilyMember next = familyMemberMapper.selectById(cursor);
            if (next == null) break;
            cursor = next.getParentFamilyMemberId();
            hops++;
        }
        if (hops >= 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上一代链过深（>100 层），疑似数据异常");
        }
    }

    private FamilyVO toVO(Family f, String myRole, Integer memberCount, Integer projectCount) {
        FamilyVO vo = new FamilyVO();
        vo.setId(f.getId());
        vo.setName(f.getName());
        vo.setDescription(f.getDescription());
        vo.setOwnerUserId(f.getOwnerUserId());
        vo.setMyRole(myRole);
        vo.setMemberCount(memberCount);
        vo.setProjectCount(projectCount);
        vo.setCreatedAt(f.getCreatedAt());
        vo.setUpdatedAt(f.getUpdatedAt());
        return vo;
    }

    // ============ 当前用户辅助（被 Controller 复用） ============

    /**
     * 公共辅助：从 SecurityContext 取当前 userId。Controller 不直接引用 CurrentUser 以避免模块耦合。
     */
    public Long currentUserId() {
        return CurrentUser.requireId();
    }
}
