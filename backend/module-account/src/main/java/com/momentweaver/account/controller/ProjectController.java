package com.momentweaver.account.controller;

import com.momentweaver.account.dto.ProjectCreateRequest;
import com.momentweaver.account.dto.ProjectUpdateRequest;
import com.momentweaver.account.dto.ProjectVO;
import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.account.service.ProjectService;
import com.momentweaver.common.PageResult;
import com.momentweaver.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "项目")
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    @Operation(summary = "创建项目")
    public Result<ProjectVO> create(@Valid @RequestBody ProjectCreateRequest req) {
        return Result.ok(projectService.create(CurrentUser.requireId(), req));
    }

    @GetMapping
    @Operation(summary = "项目列表（分页）")
    public Result<PageResult<ProjectVO>> list(
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "20") long size) {
        return Result.ok(projectService.list(CurrentUser.requireId(), page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "项目详情")
    public Result<ProjectVO> get(@PathVariable Long id) {
        return Result.ok(projectService.get(CurrentUser.requireId(), id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除项目（仅 Owner）")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(CurrentUser.requireId(), id);
        return Result.ok();
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改项目名称 / 描述（工作区成员）")
    public Result<ProjectVO> update(@PathVariable Long id, @Valid @RequestBody ProjectUpdateRequest req) {
        return Result.ok(projectService.update(CurrentUser.requireId(), id, req));
    }
}
