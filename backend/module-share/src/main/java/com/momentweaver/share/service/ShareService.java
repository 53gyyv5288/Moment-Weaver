package com.momentweaver.share.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.User;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.UserMapper;
import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.account.security.ProjectAccessChecker;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.common.event.NotificationRequest;
import com.momentweaver.common.event.NotificationTypes;
import com.momentweaver.share.dto.CreateShareRequest;
import com.momentweaver.share.dto.PublicShareVO;
import com.momentweaver.share.dto.ShareLinkVO;
import com.momentweaver.share.entity.ShareLink;
import com.momentweaver.share.event.ShareAccessedEvent;
import com.momentweaver.share.event.ShareCreatedEvent;
import com.momentweaver.share.mapper.ShareLinkMapper;
import com.momentweaver.timeline.entity.NarrativeDraft;
import com.momentweaver.timeline.repo.NarrativeDraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M5-A 分享链接服务。
 *
 * <p>职责：
 * - owner 端 CRUD（创建 / 列表 / 撤销）
 * - 公开端读取（预览 / 密码验证 / 拉取成稿内容）
 * - token 生成 + 密码 BCrypt
 * - view_count / last_accessed_at 维护
 * - 限流：同 IP 同 token 1 分钟内最多 30 次访问
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24;   // 24 bytes → 32 chars base64url (no padding)
    private static final int RATE_LIMIT_PER_MIN = 30;

    private final ShareLinkMapper shareLinkMapper;
    private final NarrativeDraftRepository draftRepository;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final ProjectAccessChecker accessChecker;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final com.momentweaver.share.config.ShareProperties shareProperties;

    /** 简易内存限流：{ token + "_" + ip → [timestamps] }。单机够用，M5 不上 Redis。 */
    private final Map<String, java.util.Deque<Long>> rateLimitBuckets = new ConcurrentHashMap<>();

    // ============== owner 端 ==============

    @Transactional
    public ShareLinkVO create(Long projectId, CreateShareRequest req) {
        Long userId = CurrentUser.requireId();
        // M10+ 替代 WorkspaceAccessChecker.requireProjectMember（只查 workspace_member）
        accessChecker.requireMember(projectId, userId);

        // 校验 draft 存在且属于该 project
        NarrativeDraft draft = draftRepository.findById(req.getDraftId())
            .orElseThrow(() -> new BusinessException(ResultCode.SHARE_LINK_DRAFT_NOT_FOUND));
        if (!projectId.toString().equals(draft.getProjectId())) {
            throw new BusinessException(ResultCode.SHARE_LINK_DRAFT_NOT_FOUND);
        }
        // 仅 published 状态的 draft 可分享（M5 简化：草稿不能分享）
        if (!"published".equals(draft.getStatus())) {
            throw new BusinessException(ResultCode.PDF_DRAFT_NOT_PUBLISHED, "仅已发布成稿可分享");
        }

        String token = generateUniqueToken();
        ShareLink link = new ShareLink();
        link.setProjectId(projectId);
        link.setDraftId(req.getDraftId());
        link.setSubjectIds(req.getSubjectIds());
        link.setToken(token);
        link.setScope(req.getScope());
        if ("password".equals(req.getScope())) {
            if (req.getPassword() == null || req.getPassword().length() < 4 || req.getPassword().length() > 32) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "密码长度需在 4-32 字符之间");
            }
            link.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        }
        link.setAllowCopy(Boolean.TRUE.equals(req.getAllowCopy()));
        link.setAllowDownload(Boolean.TRUE.equals(req.getAllowDownload()));
        link.setViewCount(0);
        link.setRevoked(false);
        link.setCreatedByName(resolveUserDisplayName(userId));
        int days = req.getExpiresInDays() == null ? shareProperties.getDefaultExpiresDays() : req.getExpiresInDays();
        link.setExpiresAt(LocalDateTime.now().plusDays(days));
        shareLinkMapper.insert(link);

        Map<String, Object> meta = new HashMap<>();
        meta.put("draftId", req.getDraftId());
        meta.put("scope", req.getScope());
        meta.put("expiresAt", link.getExpiresAt() == null ? null : link.getExpiresAt().toString());
        eventPublisher.publishEvent(new ShareCreatedEvent(userId, link.getId(), projectId, req.getDraftId(), req.getScope(), meta));

        // 通知 project owner（自己创建的就不发，避免噪音）
        Long projectOwnerId = resolveProjectOwnerId(projectId);
        if (projectOwnerId != null && !projectOwnerId.equals(userId)) {
            String scopeLabel = "password".equals(req.getScope()) ? "🔒 密码" : "🌐 公开";
            String body = String.format("《%s》%s · %d 天后到期", draft.getTitle(), scopeLabel, days);
            eventPublisher.publishEvent(new NotificationRequest(
                projectOwnerId,
                NotificationTypes.SHARE_CREATED,
                "新分享链接",
                body,
                link.getId().toString(),
                "/projects/" + projectId + "/shares",
                meta
            ));
        }

        log.info("share.created: id={} projectId={} draftId={} scope={} by user={}",
            link.getId(), projectId, req.getDraftId(), req.getScope(), userId);
        return toVO(link, draft.getTitle());
    }

    public List<ShareLinkVO> listByProject(Long projectId) {
        Long userId = CurrentUser.requireId();
        accessChecker.requireMember(projectId, userId);
        List<ShareLink> links = shareLinkMapper.selectList(new LambdaQueryWrapper<ShareLink>()
            .eq(ShareLink::getProjectId, projectId)
            .orderByDesc(ShareLink::getCreatedAt));
        // 仅返回可见信息（含 token；owner 端需要）
        return links.stream().map(l -> {
            String title = draftRepository.findById(l.getDraftId())
                .map(NarrativeDraft::getTitle).orElse(null);
            return toVO(l, title);
        }).toList();
    }

    @Transactional
    public void revoke(Long shareId) {
        Long userId = CurrentUser.requireId();
        ShareLink link = mustGet(shareId);
        // M10+ 替代 requireProjectOwner（只查 workspace_member）
        accessChecker.requireOwner(link.getProjectId(), userId);
        if (Boolean.TRUE.equals(link.getRevoked())) {
            return; // 幂等
        }
        shareLinkMapper.update(null, new LambdaUpdateWrapper<ShareLink>()
            .eq(ShareLink::getId, shareId)
            .set(ShareLink::getRevoked, true));
        log.info("share.revoked: id={} by user={}", shareId, userId);
    }

    // ============== 公开端 ==============

    /**
     * 公开端预览（仅元信息：标题、是否有密码、是否过期、allowCopy/Download）。
     * 不返回 draft 内容。
     */
    public PublicShareVO preview(String token, String ip) {
        rateLimit(token, ip);
        ShareLink link = mustGetByToken(token);
        ensureAccessible(link);

        PublicShareVO vo = new PublicShareVO();
        vo.setToken(token);
        vo.setDraftId(link.getDraftId());
        vo.setScope(link.getScope());
        vo.setAllowCopy(link.getAllowCopy());
        vo.setAllowDownload(link.getAllowDownload());
        vo.setCreatedByName(link.getCreatedByName());
        vo.setCreatedAt(formatIso(link.getCreatedAt()));
        vo.setExpiresAt(formatIso(link.getExpiresAt()));
        vo.setHasAiContent(true);
        vo.setAiLabel("本文含 AI 生成内容");

        // draft 标题（公开端可见，但 sections 在 verify 之后才给）
        draftRepository.findById(link.getDraftId()).ifPresent(d -> vo.setDraftTitle(d.getTitle()));
        return vo;
    }

    /**
     * 公开端密码验证。
     * 返回：成功 → 共享 accessToken（简单方案：把 passwordHash 校验后把内部 sessionId 放本地缓存；M5 简化版只返回成功标志，前端再调 /access）。
     * M5 简化：直接返回成功 + 调用 access() 取内容。
     */
    public PublicShareVO verifyAndAccess(String token, String password, String ip) {
        rateLimit(token, ip);
        ShareLink link = mustGetByToken(token);
        ensureAccessible(link);

        if ("password".equals(link.getScope())) {
            if (password == null || !passwordEncoder.matches(password, link.getPasswordHash())) {
                throw new BusinessException(ResultCode.SHARE_LINK_PASSWORD_INVALID);
            }
        }
        return accessInternal(link, ip, "verify");
    }

    /**
     * 公开端取完整内容（用于 public scope 或 verify 已通过的 session）。
     * M5 简化：前端 verify 后用返回的 vo.sections；如直接调本端点（public scope），也直接返回。
     */
    public PublicShareVO access(String token, String ip) {
        rateLimit(token, ip);
        ShareLink link = mustGetByToken(token);
        ensureAccessible(link);
        if ("password".equals(link.getScope())) {
            // M5 简化版：要求必须先 verify；这里抛错
            throw new BusinessException(ResultCode.SHARE_LINK_PASSWORD_INVALID, "请先验证密码");
        }
        return accessInternal(link, ip, "full");
    }

    private PublicShareVO accessInternal(ShareLink link, String ip, String accessType) {
        PublicShareVO vo = preview(link.getToken(), ip);  // 复用基础元信息
        NarrativeDraft draft = draftRepository.findById(link.getDraftId())
            .orElseThrow(() -> new BusinessException(ResultCode.SHARE_LINK_DRAFT_NOT_FOUND));
        vo.setDraftTitle(draft.getTitle());
        vo.setSections(draft.getSections().stream()
            .map(s -> {
                PublicShareVO.PublicSection ps = new PublicShareVO.PublicSection();
                ps.setSectionId(s.getSectionId());
                ps.setSectionTitle(s.getSectionTitle());
                ps.setOrder(s.getOrder());
                ps.setContent(s.getContent());
                ps.setProvenance(s.getProvenance());
                ps.setAiGenerated(s.getAiGenerated());
                return ps;
            })
            .toList());

        // 计数 + 写 lastAccessedAt
        shareLinkMapper.update(null, new LambdaUpdateWrapper<ShareLink>()
            .eq(ShareLink::getId, link.getId())
            .setSql("view_count = view_count + 1")
            .set(ShareLink::getLastAccessedAt, LocalDateTime.now()));
        eventPublisher.publishEvent(new ShareAccessedEvent(
            link.getId(),
            resolveProjectOwnerId(link.getProjectId()),
            ip,
            null,                       // userAgent 可后续从 HttpServletRequest 注入
            accessType));
        return vo;
    }

    // ============== helpers ==============

    private ShareLink mustGet(Long id) {
        ShareLink link = shareLinkMapper.selectById(id);
        if (link == null) {
            throw new BusinessException(ResultCode.SHARE_LINK_NOT_FOUND);
        }
        return link;
    }

    private ShareLink mustGetByToken(String token) {
        ShareLink link = shareLinkMapper.selectOne(new LambdaQueryWrapper<ShareLink>()
            .eq(ShareLink::getToken, token));
        if (link == null) {
            throw new BusinessException(ResultCode.SHARE_LINK_NOT_FOUND);
        }
        return link;
    }

    private void ensureAccessible(ShareLink link) {
        if (Boolean.TRUE.equals(link.getRevoked())) {
            throw new BusinessException(ResultCode.SHARE_LINK_REVOKED);
        }
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.SHARE_LINK_EXPIRED);
        }
    }

    private void rateLimit(String token, String ip) {
        String key = token + "_" + (ip == null ? "unknown" : ip);
        long nowMs = System.currentTimeMillis();
        java.util.Deque<Long> bucket = rateLimitBuckets.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
        synchronized (bucket) {
            long cutoff = nowMs - 60_000L;
            while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) {
                bucket.pollFirst();
            }
            if (bucket.size() >= RATE_LIMIT_PER_MIN) {
                throw new BusinessException(ResultCode.SHARE_LINK_RATE_LIMIT);
            }
            bucket.addLast(nowMs);
        }
    }

    private String generateUniqueToken() {
        for (int i = 0; i < 5; i++) {
            byte[] buf = new byte[TOKEN_BYTES];
            SECURE_RANDOM.nextBytes(buf);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
            // 唯一性检查（DB unique key 会兜底）
            Long count = shareLinkMapper.selectCount(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getToken, token));
            if (count == null || count == 0) {
                return token;
            }
        }
        throw new BusinessException(ResultCode.SYSTEM_ERROR, "生成 token 失败，请重试");
    }

    private String resolveUserDisplayName(Long userId) {
        User u = userMapper.selectById(userId);
        return u == null ? null : u.getDisplayName();
    }

    private Long resolveProjectOwnerId(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        return p == null ? null : p.getOwnerId();
    }

    private ShareLinkVO toVO(ShareLink link, String draftTitle) {
        ShareLinkVO vo = new ShareLinkVO();
        vo.setId(link.getId());
        vo.setProjectId(link.getProjectId());
        vo.setDraftId(link.getDraftId());
        vo.setDraftTitle(draftTitle);
        vo.setScope(link.getScope());
        vo.setToken(link.getToken());
        vo.setShareUrl(shareProperties.getPublicBaseUrl() + "/share/" + link.getToken());
        vo.setAllowCopy(link.getAllowCopy());
        vo.setAllowDownload(link.getAllowDownload());
        vo.setViewCount(link.getViewCount());
        vo.setCreatedByName(link.getCreatedByName());
        vo.setCreatedAt(link.getCreatedAt());
        vo.setExpiresAt(link.getExpiresAt());
        vo.setLastAccessedAt(link.getLastAccessedAt());
        // status: active | expired | revoked
        if (Boolean.TRUE.equals(link.getRevoked())) {
            vo.setStatus("revoked");
        } else if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
            vo.setStatus("expired");
        } else {
            vo.setStatus("active");
        }
        vo.setHasPassword("password".equals(link.getScope()));
        return vo;
    }

    private String formatIso(LocalDateTime dt) {
        return dt == null ? null : dt.toString();
    }
}
