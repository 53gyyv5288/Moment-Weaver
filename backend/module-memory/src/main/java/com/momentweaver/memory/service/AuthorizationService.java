package com.momentweaver.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.memory.dto.AuthorizationCreateRequest;
import com.momentweaver.memory.dto.AuthorizationVO;
import com.momentweaver.memory.entity.Authorization;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.AuthorizationMapper;
import com.momentweaver.memory.mapper.SubjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private static final Set<String> VALID_SCOPES = Set.of("interview", "narrative", "asset", "share");
    private static final String CHARS = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private final AuthorizationMapper authorizationMapper;
    private final SubjectMapper subjectMapper;
    private final ProjectMapper projectMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;

    @Value("${moment.consent.current-version}")
    private String consentVersion;

    @Value("${moment.authz.default-ttl-days:7}")
    private int defaultTtlDays;

    @Value("${moment.authz.public-base-url:http://localhost:5173}")
    private String publicBaseUrl;

    @Transactional
    public AuthorizationVO create(Long userId, Long projectId, AuthorizationCreateRequest req) {
        Project p = mustProject(projectId);
        if (!p.getOwnerId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅项目 Owner 可发起授权");
        }
        Subject s = mustSubject(req.getSubjectId());
        if (!s.getProjectId().equals(projectId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "人物不属于该项目");
        }

        // 校验 scopes
        List<String> valid = req.getScopes().stream()
            .map(String::trim)
            .filter(sc -> !sc.isEmpty())
            .distinct()
            .toList();
        if (valid.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "授权范围不能为空");
        }
        for (String sc : valid) {
            if (!VALID_SCOPES.contains(sc)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的授权范围: " + sc);
            }
        }

        // 同一 subject 若已存在 pending 授权，先 revoke 旧的
        Authorization old = authorizationMapper.selectOne(
            new LambdaQueryWrapper<Authorization>()
                .eq(Authorization::getSubjectId, s.getId())
                .eq(Authorization::getStatus, "pending")
        );
        if (old != null) {
            old.setStatus("revoked");
            old.setRevokedAt(LocalDateTime.now());
            old.setUpdatedAt(LocalDateTime.now());
            authorizationMapper.updateById(old);
        }

        int ttl = req.getTtlDays() != null ? req.getTtlDays() : defaultTtlDays;
        LocalDateTime now = LocalDateTime.now();

        Authorization a = new Authorization();
        a.setSubjectId(s.getId());
        a.setProjectId(projectId);
        a.setToken(generateToken());
        a.setScopes(String.join(",", valid));
        a.setStatus("pending");
        a.setConsentVersion(consentVersion);
        a.setExpiresAt(now.plusDays(ttl));
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        authorizationMapper.insert(a);

        return toVO(a, buildPublicUrl(a.getToken()));
    }

    public List<AuthorizationVO> listByProject(Long userId, Long projectId) {
        Project p = mustProject(projectId);
        ensureMember(p.getWorkspaceId(), userId);

        List<Authorization> all = authorizationMapper.selectList(
            new LambdaQueryWrapper<Authorization>()
                .eq(Authorization::getProjectId, projectId)
                .orderByDesc(Authorization::getCreatedAt)
        );
        return all.stream().map(a -> toVO(a, null)).toList();
    }

    public List<AuthorizationVO> listBySubject(Long userId, Long subjectId) {
        Subject s = mustSubject(subjectId);
        Project p = mustProject(s.getProjectId());
        ensureMember(p.getWorkspaceId(), userId);

        List<Authorization> all = authorizationMapper.selectList(
            new LambdaQueryWrapper<Authorization>()
                .eq(Authorization::getSubjectId, subjectId)
                .orderByDesc(Authorization::getCreatedAt)
        );
        return all.stream().map(a -> toVO(a, null)).toList();
    }

    @Transactional
    public void revoke(Long userId, Long authorizationId) {
        Authorization a = mustAuth(authorizationId);
        Project p = mustProject(a.getProjectId());
        if (!p.getOwnerId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅项目 Owner 可撤销授权");
        }
        if ("granted".equals(a.getStatus())) {
            // 撤销已授权的，要审计：保留记录
            a.setStatus("revoked");
            a.setRevokedAt(LocalDateTime.now());
            a.setUpdatedAt(LocalDateTime.now());
            authorizationMapper.updateById(a);
        } else if ("pending".equals(a.getStatus())) {
            a.setStatus("revoked");
            a.setRevokedAt(LocalDateTime.now());
            a.setUpdatedAt(LocalDateTime.now());
            authorizationMapper.updateById(a);
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前状态不可撤销: " + a.getStatus());
        }
    }

    // ====== 公开端（无 JWT） ======

    public Authorization fetchByToken(String token) {
        Authorization a = authorizationMapper.selectOne(
            new LambdaQueryWrapper<Authorization>().eq(Authorization::getToken, token)
        );
        if (a == null) throw new BusinessException(ResultCode.AUTHORIZATION_NOT_FOUND);
        return a;
    }

    public Authorization mustUsableByToken(String token) {
        Authorization a = fetchByToken(token);
        if (!"pending".equals(a.getStatus())) {
            throw new BusinessException(ResultCode.AUTHORIZATION_INVALID, "授权已" + statusLabel(a.getStatus()));
        }
        if (a.getExpiresAt() != null && a.getExpiresAt().isBefore(LocalDateTime.now())) {
            // 顺手标记 expired
            a.setStatus("expired");
            a.setUpdatedAt(LocalDateTime.now());
            authorizationMapper.updateById(a);
            throw new BusinessException(ResultCode.AUTHORIZATION_INVALID, "授权链接已过期");
        }
        return a;
    }

    @Transactional
    public Authorization grant(String token, String ip, String ua) {
        Authorization a = mustUsableByToken(token);
        a.setStatus("granted");
        a.setGrantedAt(LocalDateTime.now());
        a.setIp(ip);
        a.setUa(truncate(ua, 500));
        a.setUpdatedAt(LocalDateTime.now());
        authorizationMapper.updateById(a);
        return a;
    }

    @Transactional
    public Authorization deny(String token, String ip, String ua) {
        Authorization a = mustUsableByToken(token);
        a.setStatus("denied");
        a.setRevokedAt(LocalDateTime.now());
        a.setIp(ip);
        a.setUa(truncate(ua, 500));
        a.setUpdatedAt(LocalDateTime.now());
        authorizationMapper.updateById(a);
        return a;
    }

    // ---- helpers ----

    private String generateToken() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(CHARS.charAt(RNG.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String buildPublicUrl(String token) {
        return publicBaseUrl.replaceAll("/+$", "") + "/authz/" + token;
    }

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

    private Authorization mustAuth(Long id) {
        Authorization a = authorizationMapper.selectById(id);
        if (a == null) throw new BusinessException(ResultCode.AUTHORIZATION_NOT_FOUND);
        return a;
    }

    private void ensureMember(Long workspaceId, Long userId) {
        Long cnt = workspaceMemberMapper.selectCount(
            new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId)
        );
        if (cnt == null || cnt == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非工作区成员");
        }
    }

    public AuthorizationVO toVO(Authorization a, String publicUrl) {
        AuthorizationVO vo = new AuthorizationVO();
        vo.setId(a.getId());
        vo.setSubjectId(a.getSubjectId());
        vo.setProjectId(a.getProjectId());
        vo.setToken(a.getToken());
        vo.setScopes(a.getScopes() == null ? Collections.emptyList() : Arrays.asList(a.getScopes().split(",")));
        vo.setStatus(a.getStatus());
        vo.setConsentVersion(a.getConsentVersion());
        vo.setGrantedAt(a.getGrantedAt());
        vo.setRevokedAt(a.getRevokedAt());
        vo.setExpiresAt(a.getExpiresAt());
        vo.setPublicUrl(publicUrl);
        vo.setCreatedAt(a.getCreatedAt());
        return vo;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String statusLabel(String s) {
        if (s == null) return "未知";
        return switch (s) {
            case "pending" -> "待定";
            case "granted" -> "已同意";
            case "denied" -> "已拒绝";
            case "revoked" -> "已撤销";
            case "expired" -> "已过期";
            default -> s;
        };
    }
}
