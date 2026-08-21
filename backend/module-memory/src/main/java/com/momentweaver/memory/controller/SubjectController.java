package com.momentweaver.memory.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.memory.dto.EligibleFamilyMemberVO;
import com.momentweaver.memory.dto.SubjectCreateRequest;
import com.momentweaver.memory.dto.SubjectTreeResponse;
import com.momentweaver.memory.dto.SubjectUpdateRequest;
import com.momentweaver.memory.dto.SubjectVO;
import com.momentweaver.memory.service.SubjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "人物 / Subject")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/subjects")
@RequiredArgsConstructor
public class SubjectController {

    private final SubjectService subjectService;

    @PostMapping
    @Operation(summary = "新增被采访者")
    public Result<SubjectVO> create(@PathVariable Long projectId,
                                    @Valid @RequestBody SubjectCreateRequest req) {
        return Result.ok(subjectService.create(CurrentUser.requireId(), projectId, req));
    }

    @GetMapping
    @Operation(summary = "项目的被采访者列表")
    public Result<List<SubjectVO>> list(@PathVariable Long projectId) {
        return Result.ok(subjectService.list(CurrentUser.requireId(), projectId));
    }

    /**
     * M11 Phase 2：列出项目下「可选被采访者」（从家族成员筛）。
     * 前端"添加人物"弹窗 Tab 1 调用此接口；个人项目返回空列表。
     */
    @GetMapping("/eligible")
    @Operation(summary = "可选被采访者（从家族成员筛）")
    public Result<List<EligibleFamilyMemberVO>> listEligible(@PathVariable Long projectId) {
        return Result.ok(subjectService.listEligibleFamilyMembers(CurrentUser.requireId(), projectId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "被采访者详情")
    public Result<SubjectVO> get(@PathVariable Long projectId, @PathVariable Long id) {
        return Result.ok(subjectService.get(CurrentUser.requireId(), id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "局部更新人物（只改传过来的字段）")
    public Result<SubjectVO> update(@PathVariable Long projectId,
                                    @PathVariable Long id,
                                    @Valid @RequestBody SubjectUpdateRequest req) {
        return Result.ok(subjectService.update(CurrentUser.requireId(), id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除被采访者（仅项目 Owner）")
    public Result<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        subjectService.delete(CurrentUser.requireId(), id);
        return Result.ok();
    }

    /**
     * M14+ 家族关系图：项目级聚合节点（扁平数组 + orphans + warnings）。
     * 前端 d3.stratify 自建树；换可视化库不用改后端。
     */
    @GetMapping("/tree")
    @Operation(summary = "家族关系图（项目级）")
    public Result<SubjectTreeResponse> tree(@PathVariable Long projectId) {
        return Result.ok(subjectService.listTree(CurrentUser.requireId(), projectId));
    }
}
