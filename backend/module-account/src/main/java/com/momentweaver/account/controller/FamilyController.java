package com.momentweaver.account.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.dto.AdminCreateUserRequest;
import com.momentweaver.account.dto.AdminCreateUserResponse;
import com.momentweaver.account.dto.CreateFamilyRequest;
import com.momentweaver.account.dto.FamilyMemberVO;
import com.momentweaver.account.dto.FamilyVO;
import com.momentweaver.account.dto.ProjectVO;
import com.momentweaver.account.dto.UpdateFamilyMemberRequest;
import com.momentweaver.account.dto.UpdateFamilyRequest;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.account.security.FamilyAccessChecker;
import com.momentweaver.account.service.FamilyService;
import com.momentweaver.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 家族接口（M10+ Family Phase 1）。
 *
 * <p>路径前缀：{@code /api/v1/families}
 * <p>白名单：所有接口都需要登录（SecurityConfig 里默认 authenticated()）
 */
@Tag(name = "家族")
@RestController
@RequestMapping("/api/v1/families")
@RequiredArgsConstructor
public class FamilyController {

    private final FamilyService familyService;
    private final ProjectMapper projectMapper;
    private final FamilyAccessChecker familyAccessChecker;

    @PostMapping
    @Operation(summary = "创建家族（创建者自动成为 admin）")
    public Result<FamilyVO> create(@Valid @RequestBody CreateFamilyRequest req) {
        return Result.ok(familyService.create(CurrentUser.requireId(), req));
    }

    @GetMapping
    @Operation(summary = "我加入的家族列表")
    public Result<List<FamilyVO>> listMine() {
        return Result.ok(familyService.listMine(CurrentUser.requireId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "家族详情")
    public Result<FamilyVO> get(@PathVariable Long id) {
        return Result.ok(familyService.get(CurrentUser.requireId(), id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "改家族名/描述（仅 admin）")
    public Result<FamilyVO> update(@PathVariable Long id,
                                   @Valid @RequestBody UpdateFamilyRequest req) {
        return Result.ok(familyService.update(CurrentUser.requireId(), id, req));
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "成员列表（仅家族成员可看）")
    public Result<List<FamilyMemberVO>> listMembers(@PathVariable Long id) {
        return Result.ok(familyService.listMembers(CurrentUser.requireId(), id));
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "管理员创建账号并加入家族")
    public Result<AdminCreateUserResponse> adminCreateMember(@PathVariable Long id,
                                                             @Valid @RequestBody AdminCreateUserRequest req) {
        return Result.ok(familyService.adminCreateUser(CurrentUser.requireId(), id, req));
    }

    @PutMapping("/{id}/members/{userId}")
    @Operation(summary = "管理员修改成员角色/重置密码")
    public Result<FamilyMemberVO> updateMember(@PathVariable Long id,
                                               @PathVariable Long userId,
                                               @Valid @RequestBody UpdateFamilyMemberRequest req) {
        return Result.ok(familyService.updateMember(CurrentUser.requireId(), id, userId, req));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @Operation(summary = "管理员移除成员（不会删除 user 账号本身）")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        familyService.removeMember(CurrentUser.requireId(), id, userId);
        return Result.ok();
    }

    @GetMapping("/{id}/projects")
    @Operation(summary = "家族下的项目列表（所有家族成员可见）")
    public Result<List<ProjectVO>> listFamilyProjects(@PathVariable Long id) {
        Long userId = CurrentUser.requireId();
        familyAccessChecker.requireMember(id, userId);
        List<Project> projects = projectMapper.selectList(
            new LambdaQueryWrapper<Project>()
                .eq(Project::getFamilyId, id)
                .orderByDesc(Project::getUpdatedAt)
        );
        List<ProjectVO> vos = projects.stream().map(p -> {
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
            return vo;
        }).toList();
        return Result.ok(vos);
    }
}
