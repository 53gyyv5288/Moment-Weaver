package com.momentweaver.account.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.FamilyMember;
import com.momentweaver.account.mapper.FamilyMemberMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 家族成员鉴权组件（M10+ Family）。
 *
 * <p>与 {@link WorkspaceAccessChecker} 平行存在：
 *   <ul>
 *     <li>{@link WorkspaceAccessChecker} —— workspace 维度的鉴权（个人项目）</li>
 *     <li>{@link FamilyAccessChecker}    —— family 维度的鉴权（家族项目）</li>
 *   </ul>
 *
 * <p>返回 {@link FamilyMember} 而不是 void —— 调用方经常需要 myRole 做业务判断。
 */
@Component
@RequiredArgsConstructor
public class FamilyAccessChecker {

    private final FamilyMemberMapper familyMemberMapper;

    /**
     * 校验 userId 是 familyId 的成员；返回成员记录（含 role）。
     * 失败抛 FAMILY_NOT_MEMBER。
     */
    public FamilyMember requireMember(Long familyId, Long userId) {
        if (familyId == null || userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        FamilyMember m = familyMemberMapper.selectOne(
            new LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getUserId, userId)
        );
        if (m == null) {
            throw new BusinessException(ResultCode.FAMILY_NOT_MEMBER);
        }
        return m;
    }

    /**
     * 校验 userId 是 familyId 的 admin。失败抛 FAMILY_ONLY_ADMIN。
     */
    public void requireAdmin(Long familyId, Long userId) {
        FamilyMember m = requireMember(familyId, userId);
        if (!"admin".equals(m.getRole())) {
            throw new BusinessException(ResultCode.FAMILY_ONLY_ADMIN);
        }
    }

    /**
     * 校验 userId 在 familyId 中可写（即 admin / editor，viewer 不可）。
     * <p>viewer 调用写操作时抛 FAMILY_VIEWER_READONLY。
     */
    public void requireEditor(Long familyId, Long userId) {
        FamilyMember m = requireMember(familyId, userId);
        if ("viewer".equals(m.getRole())) {
            throw new BusinessException(ResultCode.FAMILY_VIEWER_READONLY);
        }
    }

    /**
     * 静默版：仅返回 role，不抛异常（用于菜单权限等可选检查）。
     * 返回 null 表示非成员。
     */
    public String getRoleOrNull(Long familyId, Long userId) {
        if (familyId == null || userId == null) return null;
        FamilyMember m = familyMemberMapper.selectOne(
            new LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getFamilyId, familyId)
                .eq(FamilyMember::getUserId, userId)
        );
        return m == null ? null : m.getRole();
    }
}
