package com.momentweaver.heartcove.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.account.security.ProjectAccessChecker;
import com.momentweaver.common.Result;
import com.momentweaver.heartcove.dto.EnabledHeartcoveSubjectVO;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.SubjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 心声信箱聚合端点（个人中心）。
 *
 * <p>设计：单次 SQL + 单次内存聚合，避免前端 N+1。
 * 前端 HeartcoveEntry 只调这一个接口。</p>
 */
@Tag(name = "心声信箱 / Heartcove My")
@RestController
@RequestMapping("/api/v1/heartcove")
@RequiredArgsConstructor
public class HeartcoveMyController {

    private final SubjectMapper subjectMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessChecker projectAccessChecker;

    /**
     * 列出当前用户可访问的、已开启心声信箱的所有 subject（跨项目/家族聚合）。
     *
     * <p>权限：
     *   <ul>
     *     <li>个人项目：subject 所属 project.ownerId == userId</li>
     *     <li>家族项目：subject 所属 project.familyId 在 userId 所属家族里</li>
     *   </ul>
     * </p>
     */
    @GetMapping("/my-enabled-subjects")
    @Operation(summary = "当前用户所有已开启心信箱的人物（单次聚合）")
    public Result<List<EnabledHeartcoveSubjectVO>> listMyEnabledSubjects() {
        Long userId = CurrentUser.requireId();

        // 1) 直接查所有已开启的 subject；权限过滤在内存里做（MySQL 层面要 JOIN 太多表，且量级不大）
        List<Subject> allEnabled = subjectMapper.selectList(
            new LambdaQueryWrapper<Subject>()
                .eq(Subject::getHeartcoveEnabled, 1)
                .orderByDesc(Subject::getHeartcoveEnabledAt)
        );
        if (allEnabled.isEmpty()) return Result.ok(Collections.emptyList());

        // 2) 批量拿 project
        Set<Long> projectIds = allEnabled.stream()
            .map(Subject::getProjectId)
            .collect(Collectors.toSet());
        Map<Long, Project> projectMap = projectMapper.selectBatchIds(projectIds).stream()
            .collect(Collectors.toMap(Project::getId, p -> p));

        // 3) 过滤：当前用户能访问的 project（ownerId == userId 或 familyId 在用户所属家族里）
        List<EnabledHeartcoveSubjectVO> out = new ArrayList<>();
        for (Subject s : allEnabled) {
            Project p = projectMap.get(s.getProjectId());
            if (p == null) continue;
            // requireMember 是 void + 抛异常版本；权限不够直接跳过（不暴露给调用者）
            try {
                projectAccessChecker.requireMember(p.getId(), userId);
            } catch (Exception ignored) {
                continue;
            }
            EnabledHeartcoveSubjectVO vo = new EnabledHeartcoveSubjectVO();
            vo.setSubjectId(s.getId());
            vo.setSubjectDisplayName(s.getDisplayName());
            vo.setSubjectRelation(s.getRelation());
            vo.setProjectId(p.getId());
            vo.setProjectName(p.getName());
            vo.setProjectType(p.getType() == null ? null : Integer.valueOf(p.getType().hashCode()));
            vo.setFamilyId(p.getFamilyId());
            vo.setHeartcoveEnabledAt(s.getHeartcoveEnabledAt());
            vo.setConsentVersion(s.getHeartcoveConsentVersion());
            out.add(vo);
        }
        return Result.ok(out);
    }
}