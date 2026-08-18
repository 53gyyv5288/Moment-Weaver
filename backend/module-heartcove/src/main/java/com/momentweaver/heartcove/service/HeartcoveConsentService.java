package com.momentweaver.heartcove.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.FamilyMember;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.FamilyMemberMapper;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.account.security.ProjectAccessChecker;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.common.event.NotificationRequest;
import com.momentweaver.heartcove.dto.HeartcoveConsentText;
import com.momentweaver.heartcove.dto.HeartcoveEnableRequest;
import com.momentweaver.heartcove.dto.HeartcoveStatusVO;
import com.momentweaver.heartcove.entity.HeartcoveAuditLog;
import com.momentweaver.heartcove.entity.HeartcoveConsent;
import com.momentweaver.heartcove.mapper.HeartcoveAuditLogMapper;
import com.momentweaver.heartcove.mapper.HeartcoveConsentMapper;
import com.momentweaver.memory.entity.InterviewSession;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.SubjectMapper;
import com.momentweaver.memory.repo.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 心声信箱授权服务：开启 / 关闭 / 状态查询。
 *
 * <p><b>开启门槛（MVP 简化版）</b>：该 subject 关联的采访会话累计用户消息 ≥ 5 轮。
 * 一期不要求发言字数。</p>
 *
 * <p><b>权限</b>：
 *   <ul>
 *     <li>个人项目：Project.owner_id 才能 enable / disable</li>
 *     <li>家族项目：family admin 才能 enable / disable</li>
 *   </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartcoveConsentService {

    /** MVP 开启门槛：该 subject 关联采访的 user 消息总条数（user role + assistant 配对算一轮，一轮 = 1 条 user） */
    public static final int MIN_INTERVIEW_TURNS = 5;

    private final SubjectMapper subjectMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessChecker projectAccessChecker;
    private final FamilyMemberMapper familyMemberMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final InterviewSessionRepository interviewSessionRepository;
    private final HeartcoveConsentMapper consentMapper;
    private final HeartcoveAuditLogMapper auditLogMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 查心声信箱状态（前端 Subject 详情页用） */
    public HeartcoveStatusVO getStatus(Long userId, Long subjectId) {
        Subject subject = mustSubject(subjectId);
        projectAccessChecker.requireMember(subject.getProjectId(), userId);

        int turns = countInterviewTurns(subject);
        HeartcoveConsent latest = latestActiveConsent(subjectId);

        HeartcoveStatusVO vo = new HeartcoveStatusVO();
        vo.setEnabled(subject.getHeartcoveEnabled() != null && subject.getHeartcoveEnabled() == 1 ? 1 : 0);
        vo.setInterviewCount(turns);
        vo.setTurnsToGo(Math.max(0, MIN_INTERVIEW_TURNS - turns));
        vo.setEnabledAt(subject.getHeartcoveEnabledAt());
        if (latest != null) {
            vo.setConsentVersion(latest.getConsentVersion());
            vo.setGrantorId(latest.getGrantorId());
        }
        return vo;
    }

    /** 开启心声信箱 */
    @Transactional
    public HeartcoveStatusVO enable(Long userId, Long subjectId, HeartcoveEnableRequest req, String ip, String ua) {
        if (req.getAgreed() == null || !req.getAgreed()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请勾选同意《数字人格授权书》");
        }
        if (!HeartcoveConsentText.VERSION.equals(req.getConsentVersion())) {
            throw new BusinessException(ResultCode.CONSENT_VERSION_OUTDATED,
                "当前授权书版本已更新，请刷新页面后重试");
        }

        Subject subject = mustSubject(subjectId);
        requireProjectAdmin(subject.getProjectId(), userId);

        if (subject.getHeartcoveEnabled() != null && subject.getHeartcoveEnabled() == 1) {
            throw new BusinessException(ResultCode.CONFLICT, "心声信箱已开启，无需重复开启");
        }

        // 1. 校验采访门槛
        int turns = countInterviewTurns(subject);
        if (turns < MIN_INTERVIEW_TURNS) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "采访未达" + MIN_INTERVIEW_TURNS + "轮，无法开启（当前 " + turns + " 轮）");
        }

        // 2. 落授权书
        HeartcoveConsent consent = new HeartcoveConsent();
        consent.setSubjectId(subjectId);
        consent.setGrantorId(userId);
        consent.setConsentVersion(req.getConsentVersion());
        consent.setScopes("chat,emotion_support");
        consent.setSignedAt(LocalDateTime.now());
        consent.setIp(ip);
        consent.setUa(ua);
        consent.setNote(req.getNote());
        LocalDateTime now = LocalDateTime.now();
        consent.setCreatedAt(now);
        consent.setUpdatedAt(now);
        consentMapper.insert(consent);

        // 3. 改 Subject 状态
        subject.setHeartcoveEnabled(1);
        subject.setHeartcoveEnabledAt(now);
        subject.setHeartcoveConsentVersion(req.getConsentVersion());
        subject.setUpdatedAt(now);
        subjectMapper.updateById(subject);

        // 4. 审计日志
        logAction(subjectId, userId, "enable",
            "开启心声邮箱（授权版本=" + req.getConsentVersion() + ", turns=" + turns + "）",
            ip, ua);

        // 5. 通知所有项目 admin（"XX 已为【人物】开启心声邮箱"）
        notifyAdmins(subject, userId);

        return getStatus(userId, subjectId);
    }

    /** 关闭心声信箱（保留 30 天软删除窗口） */
    @Transactional
    public HeartcoveStatusVO disable(Long userId, Long subjectId, String ip, String ua) {
        Subject subject = mustSubject(subjectId);
        requireProjectAdmin(subject.getProjectId(), userId);

        if (subject.getHeartcoveEnabled() == null || subject.getHeartcoveEnabled() == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "心声信箱未开启");
        }

        // 撤回所有 active 授权
        LocalDateTime now = LocalDateTime.now();
        consentMapper.selectList(
            new LambdaQueryWrapper<HeartcoveConsent>()
                .eq(HeartcoveConsent::getSubjectId, subjectId)
                .isNull(HeartcoveConsent::getRevokedAt)
        ).forEach(c -> {
            c.setRevokedAt(now);
            c.setUpdatedAt(now);
            consentMapper.updateById(c);
        });

        subject.setHeartcoveEnabled(0);
        subject.setUpdatedAt(now);
        subjectMapper.updateById(subject);

        logAction(subjectId, userId, "disable", "关闭心声邮箱", ip, ua);
        notifyAdmins(subject, userId);
        return getStatus(userId, subjectId);
    }

    // ---- helpers ----

    private Subject mustSubject(Long subjectId) {
        Subject s = subjectMapper.selectById(subjectId);
        if (s == null) throw new BusinessException(ResultCode.SUBJECT_NOT_FOUND);
        return s;
    }

    /** 校验 userId 是个人项目 owner 或家族项目 admin（沿用 ProjectAccessChecker.isProjectOwnerOrFamilyAdmin） */
    private void requireProjectAdmin(Long projectId, Long userId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        // 直接复用 isProjectOwnerOrFamilyAdmin（不抛异常的版本）
        ProjectAccessChecker pac = this.projectAccessChecker;
        if (!pac.isProjectOwnerOrFamilyAdmin(p, userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅项目 Owner 或家族管理员可操作");
        }
    }

    /** 统计该 subject 关联的所有采访会话中 user 角色的消息总数（≈ 对话轮数） */
    private int countInterviewTurns(Subject subject) {
        String subjectId = String.valueOf(subject.getId());
        List<InterviewSession> sessions = interviewSessionRepository.findBySubjectIdOrderByLastMessageAtDesc(subjectId);
        int turns = 0;
        for (InterviewSession s : sessions) {
            if (s.getMessages() == null) continue;
            for (com.momentweaver.common.entity.InterviewMessage m : s.getMessages()) {
                if ("user".equals(m.getRole())) turns++;
            }
        }
        return turns;
    }

    private HeartcoveConsent latestActiveConsent(Long subjectId) {
        return consentMapper.selectOne(
            new LambdaQueryWrapper<HeartcoveConsent>()
                .eq(HeartcoveConsent::getSubjectId, subjectId)
                .orderByDesc(HeartcoveConsent::getSignedAt)
                .last("LIMIT 1")
        );
    }

    private void logAction(Long subjectId, Long userId, String action, String detail, String ip, String ua) {
        HeartcoveAuditLog log = new HeartcoveAuditLog();
        log.setSubjectId(subjectId);
        log.setUserId(userId);
        log.setAction(action);
        log.setDetail(detail);
        log.setIp(ip);
        log.setUa(ua);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    private void notifyAdmins(Subject subject, Long actorUserId) {
        Project p = projectMapper.selectById(subject.getProjectId());
        if (p == null) return;

        String subjectName = subject.getDisplayName() == null ? "该人物" : subject.getDisplayName();
        String title = "心声邮箱状态变更";
        String body = "「" + subjectName + "」的心声邮箱已被 " + actorUserId + " 调整";
        String deepLink = "/heart-cove/" + subject.getId();

        if (p.getFamilyId() != null) {
            // 家族项目：通知所有 family admin
            List<FamilyMember> admins = familyMemberMapper.selectList(
                new LambdaQueryWrapper<FamilyMember>()
                    .eq(FamilyMember::getFamilyId, p.getFamilyId())
                    .eq(FamilyMember::getRole, "admin")
            );
            for (FamilyMember m : admins) {
                if (m.getUserId() == null || m.getUserId().equals(actorUserId)) continue;
                eventPublisher.publishEvent(new NotificationRequest(
                    m.getUserId(), "HEARTCOVE_STATUS_CHANGED", title, body,
                    String.valueOf(subject.getId()), deepLink, null));
            }
        } else {
            // 个人项目：仅通知 owner（且不等于 actor）
            if (!p.getOwnerId().equals(actorUserId)) {
                eventPublisher.publishEvent(new NotificationRequest(
                    p.getOwnerId(), "HEARTCOVE_STATUS_CHANGED", title, body,
                    String.valueOf(subject.getId()), deepLink, null));
            }
        }
    }
}