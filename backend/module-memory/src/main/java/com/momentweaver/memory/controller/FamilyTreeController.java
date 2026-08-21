package com.momentweaver.memory.controller;

import com.momentweaver.account.entity.FamilyMember;
import com.momentweaver.account.mapper.FamilyMemberMapper;
import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.account.security.FamilyAccessChecker;
import com.momentweaver.common.Result;
import com.momentweaver.memory.dto.SubjectTreeResponse;
import com.momentweaver.memory.service.SubjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * M14+ 家族关系图：家族级聚合接口。
 *
 * <p>为什么不挂 FamilyController（module-account）？</p>
 *   FamilyController 在 module-account，SubjectMapper 在 module-memory，
 *   依赖方向是 memory → account。所以这个端点必须在 module-memory 新建 controller，
 *   路径前缀仍用 {@code /api/v1/families}（Spring 不要求 controller 与路径同模块）。
 *
 * <p>接口：
 *   <ul>
 *     <li>{@code GET /api/v1/families/my-trees} → 当前用户加入的所有家族聚合树</li>
 *     <li>{@code GET /api/v1/families/{familyId}/tree} → 单家族跨项目聚合树</li>
 *   </ul>
 *
 * <p>去重口径：按 {@code family_member_id} 跨项目合并同一家族成员；有 family_member_id
 * 的 Subject 共享一个节点；匿名（family_member_id=null）的 Subject 各项目独立成节点。</p>
 */
@Tag(name = "家族树 / Family Tree")
@RestController
@RequestMapping("/api/v1/families")
@RequiredArgsConstructor
public class FamilyTreeController {

    private final SubjectService subjectService;
    private final FamilyMemberMapper familyMemberMapper;
    private final FamilyAccessChecker familyAccessChecker;

    /**
     * 当前用户加入的所有家族的家族树（聚合）。
     * <p>前端 FamilyList.vue 「家族树」tab 用。</p>
     */
    @GetMapping("/my-trees")
    @Operation(summary = "我加入的所有家族的家族树（跨项目聚合）")
    public Result<List<MyFamilyTreeVO>> myFamilyTrees() {
        Long userId = CurrentUser.requireId();
        List<FamilyMember> memberships = familyMemberMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getUserId, userId)
        );
        if (memberships.isEmpty()) return Result.ok(java.util.Collections.emptyList());

        return Result.ok(
            memberships.stream()
                .map(m -> {
                    MyFamilyTreeVO vo = new MyFamilyTreeVO();
                    vo.setFamilyId(m.getFamilyId());
                    vo.setTree(subjectService.listFamilyTree(userId, m.getFamilyId()));
                    return vo;
                })
                .toList()
        );
    }

    /**
     * 单家族家族树（跨项目聚合）。
     */
    @GetMapping("/{familyId}/tree")
    @Operation(summary = "家族关系图（家族级，跨项目聚合）")
    public Result<SubjectTreeResponse> familyTree(@PathVariable Long familyId) {
        Long userId = CurrentUser.requireId();
        // 权限：必须是家族成员（不然任何人都能拉任意家族的树）
        familyAccessChecker.requireMember(familyId, userId);
        return Result.ok(subjectService.listFamilyTree(userId, familyId));
    }

    /**
     * my-trees 响应元素：familyId + 该家族的树。
     */
    @lombok.Data
    public static class MyFamilyTreeVO {
        private Long familyId;
        private SubjectTreeResponse tree;
    }
}
