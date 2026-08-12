package com.momentweaver.memory.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.Result;
import com.momentweaver.common.ResultCode;
import com.momentweaver.memory.entity.Authorization;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.AuthorizationMapper;
import com.momentweaver.memory.mapper.SubjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 内部接口：AI 服务调 Spring 端做兜底 authz 校验（plan §2.4）。
 *
 * <p>路径已白名单到 SecurityConfig（permitAll），但本控制器内**仍然校验 X-Internal-Secret**
 * —— 共享密钥一致才信任请求，避免路径白名单被滥用泄露授权状态。
 *
 * <p>返回协议（与 ai/app/rag/authorization.py 对齐）：
 * <ul>
 *   <li>401 —— 缺少 / 错误 secret（fail-closed）</li>
 *   <li>404 —— subjectId 不存在</li>
 *   <li>403 —— userId 非 subject 所在工作区成员</li>
 *   <li>200 + {@code data.status="granted"} —— 授权有效</li>
 *   <li>200 + {@code data.status="denied"/"pending"/"revoked"/"expired"} —— 未授权</li>
 * </ul>
 */
@Slf4j
@Tag(name = "内部 Authz / Internal")
@RestController
@RequiredArgsConstructor
public class InternalAuthzController {

    private final SubjectMapper subjectMapper;
    private final ProjectMapper projectMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final AuthorizationMapper authorizationMapper;

    @Value("${moment.security.internal-secret}")
    private String internalSecret;

    @GetMapping("/api/v1/memory/subjects/{subjectId}/authorizations/check")
    @Operation(summary = "内部：校验 userId 是否对 subject 有有效授权")
    public Result<Map<String, Object>> check(@PathVariable String subjectId,
                                              @RequestParam(required = false) Long userId,
                                              HttpServletRequest req) {
        // 1) 共享密钥校验（常量时间比较，避免时序攻击）
        String provided = req.getHeader("X-Internal-Secret");
        if (provided == null || !constantTimeEquals(provided, internalSecret)) {
            log.warn("Internal authz check rejected: bad/missing secret, remote={}", req.getRemoteAddr());
            throw new BusinessException(ResultCode.UNAUTHORIZED, "internal secret 不匹配");
        }

        // 2) subjectId 必须存在
        Subject s;
        try {
            s = subjectMapper.selectById(Long.parseLong(subjectId));
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "subjectId 格式错误");
        }
        if (s == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "subject 不存在");
        }

        // 3) userId（若有）必须是 subject 所在工作区的成员
        //    AI 服务传 userId 时校验；不传时跳过（依赖 Milvus filter 兜底）
        if (userId != null) {
            Project p = projectMapper.selectById(s.getProjectId());
            if (p == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "subject 关联的项目不存在");
            }
            Long cnt = workspaceMemberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>()
                    .eq(WorkspaceMember::getWorkspaceId, p.getWorkspaceId())
                    .eq(WorkspaceMember::getUserId, userId)
            );
            if (cnt == null || cnt == 0) {
                throw new BusinessException(ResultCode.FORBIDDEN,
                    "user " + userId + " 非 subject " + subjectId + " 工作区成员");
            }
        }

        // 4) 查该 subject 是否有 granted 状态的 Authorization（未过期）
        Authorization a = authorizationMapper.selectOne(
            new LambdaQueryWrapper<Authorization>()
                .eq(Authorization::getSubjectId, s.getId())
                .eq(Authorization::getStatus, "granted")
                .orderByDesc(Authorization::getGrantedAt)
                .last("LIMIT 1")
        );

        String status;
        if (a == null) {
            status = "none";
        } else if (a.getExpiresAt() != null && a.getExpiresAt().isBefore(LocalDateTime.now())) {
            // 顺手标记 expired
            a.setStatus("expired");
            a.setUpdatedAt(LocalDateTime.now());
            authorizationMapper.updateById(a);
            status = "expired";
        } else {
            status = "granted";
        }

        log.debug("Internal authz check sid={} uid={} → status={}", subjectId, userId, status);
        return Result.ok(Map.of("status", status));
    }

    /** 常量时间字符串比较（防御时序攻击；secret 长度通常 < 64，无需太多担心但有比没有好）。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}