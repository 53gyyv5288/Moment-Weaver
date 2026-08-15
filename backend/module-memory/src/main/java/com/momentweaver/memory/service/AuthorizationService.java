package com.momentweaver.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.security.ProjectAccessChecker;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.common.event.AuthorizationRevokedEvent;
import com.momentweaver.common.event.NotificationRequest;
import com.momentweaver.common.event.NotificationTypes;
import com.momentweaver.memory.dto.AuthorizationCreateRequest;
import com.momentweaver.memory.dto.AuthorizationVO;
import com.momentweaver.memory.entity.Authorization;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.AuthorizationMapper;
import com.momentweaver.memory.mapper.SubjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private static final Set<String> VALID_SCOPES = Set.of("interview", "narrative", "asset", "share");
    private static final String CHARS = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private final AuthorizationMapper authorizationMapper;
    private final SubjectMapper subjectMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessChecker projectAccessChecker;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${moment.consent.current-version}")
    private String consentVersion;

    @Value("${moment.authz.default-ttl-days:7}")
    private int defaultTtlDays;

    @Value("${moment.authz.public-base-url:http://localhost:5173}")
    private String publicBaseUrl;

    @Transactional
    public AuthorizationVO create(Long userId, Long projectId, AuthorizationCreateRequest req) {
        Project p = mustProject(projectId);
        // 家族项目：admin 可发起；个人项目：owner 可发起
        projectAccessChecker.requireOwner(projectId, userId);
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

        // M11 Phase 2：如果被采访者关联了家族成员账号，发布站内通知直达
        // 被采访者登录后铃铛里直接看到，点一下就到同意页
        if (s.getLinkedUserId() != null && !s.getLinkedUserId().equals(userId)) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("authorizationId", a.getId());
            meta.put("subjectId", s.getId());
            meta.put("projectId", projectId);
            eventPublisher.publishEvent(new NotificationRequest(
                s.getLinkedUserId(),
                NotificationTypes.AUTHORIZATION_REQUESTED,
                "收到一份采访授权",
                String.format("邀请您作为「%s」接受采访授权（有效期 %d 天）", s.getDisplayName(), ttl),
                String.valueOf(a.getId()),
                "/authz/" + a.getToken(),   // deepLink：被采访者点击直接进同意页
                meta
            ));
            log.info("authorization.requested.notify: sid={} subjectUserId={} aid={}",
                s.getId(), s.getLinkedUserId(), a.getId());
        }

        return toVO(a, buildPublicUrl(a.getToken()));
    }

    public List<AuthorizationVO> listByProject(Long userId, Long projectId) {
        Project p = mustProject(projectId);
        projectAccessChecker.requireMember(projectId, userId);

        List<Authorization> all = authorizationMapper.selectList(
            new LambdaQueryWrapper<Authorization>()
                .eq(Authorization::getProjectId, projectId)
                .orderByDesc(Authorization::getCreatedAt)
        );
        return all.stream().map(a -> toVO(a, null)).toList();
    }

    public List<AuthorizationVO> listBySubject(Long userId, Long subjectId) {
        Subject s = mustSubject(subjectId);
        projectAccessChecker.requireMember(s.getProjectId(), userId);

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
        // 家族项目：admin 可撤销；个人项目：owner 可撤销
        projectAccessChecker.requireOwner(a.getProjectId(), userId);
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

        // M5-B.2: 发布撤回事件，让 timeline / share / notification 模块各自级联
        Project ownerOfProject = mustProject(a.getProjectId());
        Long projectOwnerId = ownerOfProject.getOwnerId();
        String subjectDisplayName = resolveSubjectDisplayName(a.getSubjectId());
        eventPublisher.publishEvent(new AuthorizationRevokedEvent(
            userId,
            a.getId(),
            a.getProjectId(),
            projectOwnerId,
            String.valueOf(a.getSubjectId()),
            subjectDisplayName,
            "Owner 主动撤销"
        ));
    }

    private String resolveSubjectDisplayName(Long subjectId) {
        if (subjectId == null) return null;
        try {
            Subject s = subjectMapper.selectById(subjectId);
            return s == null ? null : s.getDisplayName();
        } catch (Exception e) {
            return null;
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

        // M11 Phase 2：被采访者同意后通知项目 owner（让他开始采访）
        Subject subj = subjectMapper.selectById(a.getSubjectId());
        Project proj = mustProject(a.getProjectId());
        if (subj != null && proj != null && !proj.getOwnerId().equals(subj.getLinkedUserId())) {
            // 不给"自己同意自己"的被采访者重复发（owner == subject user 的边界场景）
            Map<String, Object> meta = new HashMap<>();
            meta.put("authorizationId", a.getId());
            meta.put("subjectId", a.getSubjectId());
            meta.put("projectId", a.getProjectId());
            eventPublisher.publishEvent(new NotificationRequest(
                proj.getOwnerId(),
                NotificationTypes.AUTHORIZATION_GRANTED,
                "采访授权已通过",
                String.format("「%s」已同意采访授权，可开始对话", subj.getDisplayName()),
                String.valueOf(a.getId()),
                // M11 Phase 2 修复：跳到项目详情页（这里还没有 interview session，跳 /interview/<空 id> 会 404 空页面）
                "/projects/" + proj.getId(),
                meta
            ));
        }
        return a;
    }

    /**
     * 保留接口：找该 subject 最近的 active interview session id。
     * 当前没用上，留着给未来"如果项目已存在 active session 时直接跳过去"用。
     */
    @SuppressWarnings("unused")
    private String findSessionIdForSubject(Long subjectId) {
        // 这里不依赖 InterviewService（避免循环依赖），用最简单 SQL：
        // 找该 subject 最新一个 status=active 的 session
        // 当前未实现（直接走 /projects/{id} 兜底）
        return "";
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
