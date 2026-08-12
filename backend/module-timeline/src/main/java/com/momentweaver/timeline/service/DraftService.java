package com.momentweaver.timeline.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.common.event.NotificationRequest;
import com.momentweaver.common.event.NotificationTypes;
import com.momentweaver.common.event.TimelineEventRequest;
import com.momentweaver.common.event.TimelineEventTypes;
import com.momentweaver.common.entity.InterviewMessage;
import com.momentweaver.memory.entity.InterviewSession;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.SubjectMapper;
import com.momentweaver.memory.repo.InterviewSessionRepository;
import com.momentweaver.rag.client.RagClient;
import com.momentweaver.rag.dto.EvidenceChunk;
import com.momentweaver.rag.dto.SearchRequest;
import com.momentweaver.timeline.config.AiNarrativeClient;
import com.momentweaver.timeline.dto.AiNarrativeRequest;
import com.momentweaver.timeline.dto.AiNarrativeResponse;
import com.momentweaver.timeline.dto.CreateDraftRequest;
import com.momentweaver.timeline.dto.FactSnapshotVO;
import com.momentweaver.timeline.dto.NarrativeDraftVO;
import com.momentweaver.timeline.dto.PublishDraftRequest;
import com.momentweaver.timeline.dto.SectionVO;
import com.momentweaver.timeline.dto.TemplateSpec;
import com.momentweaver.timeline.dto.UpdateSectionRequest;
import com.momentweaver.timeline.entity.Asset;
import com.momentweaver.timeline.entity.NarrativeDraft;
import com.momentweaver.timeline.mapper.AssetMapper;
import com.momentweaver.timeline.repo.NarrativeDraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 成稿服务（M4）。
 *
 * <p>阶段 1：CRUD + 范围检查 + fact 收集（不调 AI）。
 * <p>阶段 3：generate() 调 AI 把 sections 灌进 draft；updateSection() 增加
 * rewriteStyle 分支调 AI 重写；create/updateSection/publish 发 timeline 事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftService {

    private final NarrativeDraftRepository repo;
    private final ProjectMapper projectMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final SubjectMapper subjectMapper;
    private final InterviewSessionRepository sessionRepo;
    private final AssetMapper assetMapper;
    private final AiNarrativeClient aiNarrativeClient;
    private final RagClient ragClient;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 单次喂给 LLM 的 facts 上限（RAG 合并后）。
     * <p>背景：MiniMax-M3 等推理模型在 prompt 接近/超过 8K token 时
     * 容易返回空 content（200 OK 但 content=""），实测 56 条事实触发过。
     * factsSnapshot 仍然全量保存在 DB（审计 + 重写），仅在 buildAiRequest
     * 截断喂给 AI。
     *
     * <p>从 30 → 60：RAG 注入后 top-10 相关 facts 跟原 snapshot 合并，
     * 增加上限能保留更多 grounding 证据，减少幻觉。
     */
    private static final int AI_FACTS_LIMIT = 60;

    /** RAG 检索 top_k（plan §4.3.C：top-10 相关 facts）。 */
    private static final int RAG_FACTS_TOP_K = 10;

    /**
     * 创建空 draft（不调 AI）。同时收集 facts 冻入 factsSnapshot，
     * 阶段 3 的 AI 重写就能直接复用，不再回表查。
     */
    @Transactional
    public NarrativeDraftVO create(Long userId, Long projectId, CreateDraftRequest req) {
        Project p = mustProject(projectId);
        ensureMember(p.getWorkspaceId(), userId);
        validateScope(projectId, req.getSubjectIds(), deriveScope(req.getTemplateId()));
        if (!TemplateSpec.isValid(req.getTemplateId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未知模板：" + req.getTemplateId());
        }

        String scope = deriveScope(req.getTemplateId());
        List<String> subjectIdStrs = req.getSubjectIds().stream().map(String::valueOf).toList();
        List<String> displayNames = req.getSubjectIds().stream()
            .map(this::mustSubjectDisplayName)
            .toList();

        // 收集 facts（不调 AI，只把可用事实冻起来）
        List<NarrativeDraft.FactSnapshot> facts = collectFacts(req.getSubjectIds());

        // 构造空 sections 骨架（content 留空，provenance=null，等 AI 接入后再灌）
        List<NarrativeDraft.Section> sections = new ArrayList<>();
        int order = 0;
        for (TemplateSpec.SectionMeta meta : TemplateSpec.sectionsOf(req.getTemplateId())) {
            sections.add(NarrativeDraft.Section.builder()
                .sectionId(meta.getSectionId())
                .sectionTitle(meta.getSectionTitle())
                .order(order++)
                .targetCharsMin(meta.getTargetCharsMin())
                .targetCharsMax(meta.getTargetCharsMax())
                .markPolicy(meta.getMarkPolicy())
                .content("")
                .provenance(null)
                .aiGenerated(false)
                .factsUsed(new ArrayList<>())
                .rewriteCount(0)
                .build());
        }

        LocalDateTime now = LocalDateTime.now();
        NarrativeDraft d = NarrativeDraft.builder()
            .projectId(String.valueOf(projectId))
            .workspaceId(String.valueOf(p.getWorkspaceId()))
            .ownerId(String.valueOf(userId))
            .templateId(req.getTemplateId())
            .scope(scope)
            .subjectIds(subjectIdStrs)
            .subjectDisplayNames(displayNames)
            .title(req.getTitle())
            .status("pending")
            .sections(sections)
            .factsSnapshot(facts)
            .createdAt(now)
            .updatedAt(now)
            .version(1L)
            .build();

        NarrativeDraft saved = repo.save(d);
        log.info("Draft created: id={}, projectId={}, templateId={}, subjectCount={}, factCount={}",
            saved.getId(), projectId, req.getTemplateId(), subjectIdStrs.size(), facts.size());

        // 发 timeline 事件
        Map<String, Object> meta = new HashMap<>();
        meta.put("draftId", saved.getId());
        meta.put("templateId", saved.getTemplateId());
        meta.put("scope", saved.getScope());
        meta.put("subjectIds", saved.getSubjectIds());
        meta.put("sectionCount", saved.getSections() == null ? 0 : saved.getSections().size());
        eventPublisher.publishEvent(new TimelineEventRequest(
            saved.getProjectId(),
            saved.getSubjectIds() == null || saved.getSubjectIds().isEmpty()
                ? null : saved.getSubjectIds().get(0),
            TimelineEventTypes.NARRATIVE_DRAFT_CREATED,
            saved.getId(),
            "成稿「" + (saved.getTitle() == null || saved.getTitle().isBlank() ? "未命名" : saved.getTitle()) + "」已创建",
            "模板：" + saved.getTemplateId() + " · " + (saved.getSections() == null ? 0 : saved.getSections().size()) + " 章节待生成",
            meta
        ));

        return toVO(saved);
    }

    /** 列表（按 project / scope / status 过滤） */
    public List<NarrativeDraftVO> list(Long userId, Long projectId, String scope, String status,
                                       int page, int size) {
        Project p = mustProject(projectId);
        ensureMember(p.getWorkspaceId(), userId);

        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), Math.min(Math.max(1, size), 200));
        String pidStr = String.valueOf(projectId);
        List<NarrativeDraft> drafts;
        if (scope != null && status != null) {
            drafts = repo.findByProjectIdAndScopeAndStatusOrderByCreatedAtDesc(pidStr, scope, status, pageable);
        } else if (scope != null) {
            drafts = repo.findByProjectIdAndScopeOrderByCreatedAtDesc(pidStr, scope, pageable);
        } else if (status != null) {
            drafts = repo.findByProjectIdAndStatusOrderByCreatedAtDesc(pidStr, status, pageable);
        } else {
            drafts = repo.findByProjectIdOrderByCreatedAtDesc(pidStr, pageable);
        }
        return drafts.stream().map(this::toVO).toList();
    }

    /** 详情（带权限检查） */
    public NarrativeDraftVO get(Long userId, String draftId) {
        NarrativeDraft d = mustDraft(draftId);
        Project p = mustProject(Long.valueOf(d.getProjectId()));
        ensureMember(p.getWorkspaceId(), userId);
        return toVO(d);
    }

    /**
     * 人工编辑 / AI 重写 section。
     *
     * <p>分支：
     * <ul>
     *   <li>content != null → 人工编辑（provenance: ai → mixed；空 → human）</li>
     *   <li>rewriteStyle != null → AI 重写（调 AI service /regenerate-section）</li>
     * </ul>
     */
    @Transactional
    public NarrativeDraftVO updateSection(Long userId, String draftId, String sectionId,
                                          UpdateSectionRequest req, Long ifMatchVersion) {
        NarrativeDraft d = mustDraft(draftId);
        Project p = mustProject(Long.valueOf(d.getProjectId()));
        ensureMember(p.getWorkspaceId(), userId);
        checkOptimisticLock(d, ifMatchVersion);

        if (req.getContent() == null && req.getRewriteStyle() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "content 和 rewriteStyle 至少传一个");
        }
        if (req.getContent() != null && req.getRewriteStyle() != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "content 和 rewriteStyle 互斥");
        }
        if (req.getRewriteStyle() != null
            && !Set.of("warmer", "concise", "vivid", "formal").contains(req.getRewriteStyle())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "rewriteStyle 必须是 warmer | concise | vivid | formal 之一");
        }

        NarrativeDraft.Section s = findSection(d, sectionId);
        String beforeProvenance = s.getProvenance();
        Integer beforeRewrite = s.getRewriteCount() == null ? 0 : s.getRewriteCount();

        if (req.getContent() != null) {
            // 人工编辑
            s.setContent(req.getContent());
            s.setManuallyEditedAt(LocalDateTime.now());
            if (Boolean.TRUE.equals(s.getAiGenerated())) {
                s.setProvenance("mixed");
            } else {
                s.setProvenance("human");
            }
        } else {
            // AI 重写：RAG 用 section 标题 + 当前内容作 query，拿到相关 facts 再喂 LLM
            String sectionBrief = s.getSectionTitle() + " " + abbreviate(s.getContent(), 80);
            AiNarrativeRequest aiReq = buildAiRequest(d, sectionBrief);
            String newContent = aiNarrativeClient.regenerateSection(
                d.getTemplateId(), s.getSectionId(), s.getSectionTitle(),
                s.getContent(), req.getRewriteStyle(), aiReq);
            s.setContent(newContent);
            s.setLastRewriteStyle(req.getRewriteStyle());
            s.setRewriteCount(beforeRewrite + 1);
            // provenance 重置为 ai；manuallyEditedAt 保留（曾经被改过的痕迹）
            s.setProvenance("ai");
        }

        d.setUpdatedAt(LocalDateTime.now());
        d.setVersion(d.getVersion() + 1);
        if ("pending".equals(d.getStatus())) {
            d.setStatus("draft");
        }
        repo.save(d);
        log.info("Draft section updated: draftId={}, sectionId={}, provenance={}->{}, rewrite={}",
            draftId, sectionId, beforeProvenance, s.getProvenance(), s.getRewriteCount());

        // 发 timeline 事件
        Map<String, Object> meta = new HashMap<>();
        meta.put("draftId", draftId);
        meta.put("sectionId", sectionId);
        meta.put("provenance", s.getProvenance());
        meta.put("rewriteCount", s.getRewriteCount());
        eventPublisher.publishEvent(new TimelineEventRequest(
            d.getProjectId(),
            d.getSubjectIds() == null || d.getSubjectIds().isEmpty() ? null : d.getSubjectIds().get(0),
            TimelineEventTypes.NARRATIVE_DRAFT_SECTION_EDITED,
            draftId,
            "编辑了「" + s.getSectionTitle() + "」",
            truncate(s.getContent(), 50),
            meta
        ));

        return toVO(d);
    }

    /**
     * AI 生成整篇（M4 阶段 3）：调 /api/v1/narrative/generate，把 sections 灌进 draft。
     * factsSnapshot 已经在 create() 时收集好了，这里直接用。
     */
    @Transactional
    public NarrativeDraftVO generate(Long userId, String draftId) {
        NarrativeDraft d = mustDraft(draftId);
        Project p = mustProject(Long.valueOf(d.getProjectId()));
        ensureMember(p.getWorkspaceId(), userId);

        if ("published".equals(d.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已发布，不可重新生成");
        }
        if (d.getFactsSnapshot() == null || d.getFactsSnapshot().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "无可用事实，请先添加采访或素材");
        }

        // 1) 构造 AI 请求
        AiNarrativeRequest aiReq = buildAiRequest(d);

        // 2) 调 AI
        AiNarrativeResponse aiResp = aiNarrativeClient.generate(aiReq);

        // 3) 灌 sections：保留 sectionId 对应，provenance=ai, aiGenerated=true
        Map<String, AiNarrativeResponse.SectionOut> byId = new HashMap<>();
        if (aiResp.getSections() != null) {
            for (AiNarrativeResponse.SectionOut s : aiResp.getSections()) {
                byId.put(s.getSectionId(), s);
            }
        }
        for (NarrativeDraft.Section s : d.getSections()) {
            AiNarrativeResponse.SectionOut ai = byId.get(s.getSectionId());
            if (ai == null) continue;
            s.setContent(ai.getContent() == null ? "" : ai.getContent());
            s.setFactsUsed(ai.getFactsUsed() == null ? new ArrayList<>() : new ArrayList<>(ai.getFactsUsed()));
            s.setProvenance("ai");
            s.setAiGenerated(true);
            s.setRewriteCount(0);
            s.setLastRewriteStyle(null);
            // manuallyEditedAt 保留
        }
        // 4) 标题
        if (aiResp.getTitle() != null && !aiResp.getTitle().isBlank()) {
            d.setTitle(aiResp.getTitle());
        }
        d.setStatus("draft");
        d.setUpdatedAt(LocalDateTime.now());
        d.setVersion(d.getVersion() + 1);
        repo.save(d);
        log.info("Draft generated: id={}, title={}, sections={}",
            draftId, d.getTitle(), d.getSections() == null ? 0 : d.getSections().size());
        return toVO(d);
    }

    /** 发布：状态 draft → published，写 publishedAt */
    @Transactional
    public NarrativeDraftVO publish(Long userId, String draftId, PublishDraftRequest req) {
        NarrativeDraft d = mustDraft(draftId);
        Project p = mustProject(Long.valueOf(d.getProjectId()));
        ensureMember(p.getWorkspaceId(), userId);

        if ("published".equals(d.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已是发布状态");
        }
        if ("archived".equals(d.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "归档后不可发布");
        }
        boolean hasContent = d.getSections() != null && d.getSections().stream()
            .anyMatch(s -> s.getContent() != null && !s.getContent().isBlank());
        if (!hasContent) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无内容，无法发布");
        }

        if (req != null && req.getTitle() != null && !req.getTitle().isBlank()) {
            d.setTitle(req.getTitle());
        }
        d.setStatus("published");
        d.setPublishedAt(LocalDateTime.now());
        d.setUpdatedAt(d.getPublishedAt());
        d.setVersion(d.getVersion() + 1);
        repo.save(d);
        log.info("Draft published: id={}, title={}, sectionCount={}",
            draftId, d.getTitle(), d.getSections() == null ? 0 : d.getSections().size());

        // 发 timeline 事件
        Map<String, Object> meta = new HashMap<>();
        meta.put("draftId", draftId);
        meta.put("title", d.getTitle());
        meta.put("scope", d.getScope());
        eventPublisher.publishEvent(new TimelineEventRequest(
            d.getProjectId(),
            d.getSubjectIds() == null || d.getSubjectIds().isEmpty() ? null : d.getSubjectIds().get(0),
            TimelineEventTypes.NARRATIVE_DRAFT_PUBLISHED,
            draftId,
            "成稿「" + (d.getTitle() == null ? "未命名" : d.getTitle()) + "」已发布",
            truncate(joinFirstSections(d, 2), 80),
            meta
        ));

        // 发通知给工作区其他成员（不发给自己，避免噪音）
        notifyWorkspaceMembers(p.getWorkspaceId(), userId, d, draftId, meta);

        return toVO(d);
    }

    private void notifyWorkspaceMembers(Long workspaceId, Long publisherId,
                                        NarrativeDraft d, String draftId,
                                        Map<String, Object> meta) {
        if (workspaceId == null) return;
        List<WorkspaceMember> members = workspaceMemberMapper.selectList(
            new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, workspaceId)
        );
        String title = d.getTitle() == null ? "未命名" : d.getTitle();
        String body = String.format("《%s》已发布，可阅读 / 分享", title);
        for (WorkspaceMember m : members) {
            Long memberId = m.getUserId();
            if (memberId == null || memberId.equals(publisherId)) continue;
            eventPublisher.publishEvent(new NotificationRequest(
                memberId,
                NotificationTypes.DRAFT_PUBLISHED,
                "成稿已发布",
                body,
                draftId,
                "/drafts/" + draftId + "/read",
                meta
            ));
        }
    }

    // ============ AI helpers ============

    private AiNarrativeRequest buildAiRequest(NarrativeDraft d) {
        return buildAiRequest(d, null);
    }

    /**
     * 构造 AI 请求（含 RAG grounding 增强）。
     *
     * <p>plan §4.3.C：
     *   - 在 collectFacts() 返回的事实列表基础上，额外调 RAG 拉 top-N 相关 facts
     *     （filter is_curated_for_facts == true）
     *   - 合并去重后喂 LLM
     *   - factsSnapshot 仍保存全量（DB 审计），仅 buildAiRequest 截断到 AI_FACTS_LIMIT
     */
    private AiNarrativeRequest buildAiRequest(NarrativeDraft d, String sectionBrief) {
        List<AiNarrativeRequest.SubjectItem> subjects = new ArrayList<>();
        if (d.getSubjectIds() != null) {
            for (int i = 0; i < d.getSubjectIds().size(); i++) {
                String sid = d.getSubjectIds().get(i);
                String name = (d.getSubjectDisplayNames() != null
                    && i < d.getSubjectDisplayNames().size())
                    ? d.getSubjectDisplayNames().get(i) : "未命名";
                subjects.add(AiNarrativeRequest.SubjectItem.builder()
                    .subjectId(sid)
                    .name(name)
                    .build());
            }
        }

        // 1) 收集基线 facts（时间倒序截断到 AI_FACTS_LIMIT，留位置给 RAG）
        List<NarrativeDraft.FactSnapshot> baseline = new ArrayList<>();
        if (d.getFactsSnapshot() != null) {
            baseline = d.getFactsSnapshot().stream()
                .sorted((a, b) -> {
                    LocalDateTime ta = a.getTimestamp();
                    LocalDateTime tb = b.getTimestamp();
                    if (ta == null && tb == null) return 0;
                    if (ta == null) return 1;
                    if (tb == null) return -1;
                    return tb.compareTo(ta);
                })
                .limit(AI_FACTS_LIMIT - RAG_FACTS_TOP_K)  // 给 RAG 留位置
                .toList();
        }

        // 2) RAG grounding：按 sectionBrief 或 d.title 拉 top-N 相关 facts
        List<NarrativeDraft.FactSnapshot> ragFacts = fetchRagFacts(d, sectionBrief);

        // 3) 合并去重（按 factId） + 截断
        Set<String> seen = new HashSet<>();
        List<NarrativeDraft.FactSnapshot> merged = new ArrayList<>();
        // baseline 优先
        for (NarrativeDraft.FactSnapshot f : baseline) {
            if (f.getFactId() != null && seen.add(f.getFactId())) {
                merged.add(f);
            }
        }
        // RAG 补充
        for (NarrativeDraft.FactSnapshot f : ragFacts) {
            if (f.getFactId() != null && seen.add(f.getFactId())) {
                merged.add(f);
            }
            if (merged.size() >= AI_FACTS_LIMIT) break;
        }

        // 4) 转 FactItem
        List<AiNarrativeRequest.FactItem> facts = new ArrayList<>(merged.size());
        for (NarrativeDraft.FactSnapshot f : merged) {
            facts.add(AiNarrativeRequest.FactItem.builder()
                .factId(f.getFactId())
                .source(f.getSource())
                .text(f.getText())
                .subjectId(f.getSubjectId())
                .timestamp(f.getTimestamp())
                .build());
        }
        log.debug("AI request built: templateId={}, subjects={}, facts={} (baseline={}, rag={})",
            d.getTemplateId(), subjects.size(), facts.size(),
            baseline.size(), ragFacts.size());
        return AiNarrativeRequest.builder()
            .templateId(d.getTemplateId())
            .subjects(subjects)
            .facts(facts)
            .build();
    }

    /**
     * RAG：拉 top-N 相关 facts（filter is_curated_for_facts == true）。
     * 失败 / 空 → 返回空 list（baseline 已能覆盖基础需求）。
     */
    private List<NarrativeDraft.FactSnapshot> fetchRagFacts(NarrativeDraft d, String sectionBrief) {
        try {
            String query = sectionBrief;
            if (query == null || query.isBlank()) {
                // 退到 draft 标题 + subject 列表
                StringBuilder sb = new StringBuilder();
                if (d.getTitle() != null) sb.append(d.getTitle()).append(" ");
                if (d.getSubjectDisplayNames() != null) {
                    for (String n : d.getSubjectDisplayNames()) {
                        if (n != null) sb.append(n).append(" ");
                    }
                }
                query = sb.toString().trim();
            }
            if (query.isBlank() || d.getSubjectIds() == null || d.getSubjectIds().isEmpty()) {
                return List.of();
            }
            // 单 subject 取第一个；多 subject family-template 时遍历取并集
            String primarySubject = d.getSubjectIds().get(0);
            List<EvidenceChunk> chunks = ragClient.searchEvidence(
                SearchRequest.SCENARIO_NARRATIVE_FACTS, query, primarySubject,
                Long.valueOf(d.getOwnerId() == null ? "0" : d.getOwnerId()));
            if (chunks == null || chunks.isEmpty()) return List.of();
            List<NarrativeDraft.FactSnapshot> out = new ArrayList<>();
            int i = 0;
            for (EvidenceChunk c : chunks) {
                if (c.parentText() == null || c.parentText().isBlank()) continue;
                String factId = "rag-" + UUID.nameUUIDFromBytes(
                    (primarySubject + ":" + c.chunkId()).getBytes());
                out.add(NarrativeDraft.FactSnapshot.builder()
                    .factId(factId)
                    .source("rag_evidence")
                    .text(c.parentText())
                    .subjectId(primarySubject)
                    .timestamp(LocalDateTime.now())
                    .build());
                if (++i >= RAG_FACTS_TOP_K) break;
            }
            log.info("Draft {} RAG facts fetched: {} chunks (subject={})",
                d.getId(), out.size(), primarySubject);
            return out;
        } catch (Exception e) {
            log.warn("Draft {} RAG facts fetch failed (non-fatal): {}", d.getId(), e.toString());
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        s = s.strip();
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String joinFirstSections(NarrativeDraft d, int n) {
        if (d.getSections() == null) return "";
        StringBuilder sb = new StringBuilder();
        int cnt = 0;
        for (NarrativeDraft.Section s : d.getSections()) {
            if (s.getContent() == null || s.getContent().isBlank()) continue;
            if (cnt > 0) sb.append(" / ");
            sb.append(s.getSectionTitle()).append("：").append(s.getContent().strip());
            cnt++;
            if (cnt >= n) break;
        }
        return sb.toString();
    }

    // ============ 范围检查 / fact 收集 / helpers ============

    /**
     * 范围检查：
     * <ul>
     *   <li>person scope：仅 1 个 subject</li>
     *   <li>family scope：至少 1 个 subject</li>
     *   <li>所有 subjectId 必须属于该项目</li>
     * </ul>
     */
    private void validateScope(Long projectId, List<Long> subjectIds, String scope) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "subjectIds 不能为空");
        }
        if ("person".equals(scope) && subjectIds.size() != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "人物小传仅支持单 subject");
        }
        if ("family".equals(scope) && subjectIds.size() < 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "家族小传至少 1 个 subject");
        }
        for (Long sid : subjectIds) {
            mustSubjectInProject(sid, projectId);
        }
    }

    private String deriveScope(String templateId) {
        if (TemplateSpec.PERSON_V1.equals(templateId)) return "person";
        if (TemplateSpec.FAMILY_V1.equals(templateId)) return "family";
        return "unknown";
    }

    /**
     * 收集 fact 快照：3 个来源
     * <ol>
     *   <li>采访 messages（user/assistant；assistant 跳过避免 AI 互引）</li>
     *   <li>采访 summary（标题 / 金句 / 关键时间点）</li>
     *   <li>素材 caption（非空）</li>
     *   <li>subject note（非空）</li>
     * </ol>
     */
    private List<NarrativeDraft.FactSnapshot> collectFacts(List<Long> subjectIds) {
        List<NarrativeDraft.FactSnapshot> facts = new ArrayList<>();
        for (Long sid : subjectIds) {
            String sidStr = String.valueOf(sid);
            // 1) 采访 session
            List<InterviewSession> sessions = sessionRepo.findBySubjectIdOrderByLastMessageAtDesc(sidStr);
            for (InterviewSession s : sessions) {
                if (s.getSummary() != null) {
                    if (s.getSummary().getTitle() != null && !s.getSummary().getTitle().isBlank()) {
                        facts.add(NarrativeDraft.FactSnapshot.builder()
                            .factId("sum-title-" + s.getId())
                            .source("interview")
                            .text(s.getSummary().getTitle())
                            .subjectId(sidStr)
                            .timestamp(s.getSummary().getGeneratedAt())
                            .build());
                    }
                    for (String q : s.getSummary().getGoldenQuotes()) {
                        if (q != null && !q.isBlank()) {
                            facts.add(NarrativeDraft.FactSnapshot.builder()
                                .factId("sum-quote-" + s.getId() + "-" + Integer.toHexString(q.hashCode()))
                                .source("interview")
                                .text(q)
                                .subjectId(sidStr)
                                .timestamp(s.getSummary().getGeneratedAt())
                                .build());
                        }
                    }
                    for (InterviewSession.KeyMoment km : s.getSummary().getKeyMoments()) {
                        if (km.getText() != null && !km.getText().isBlank()) {
                            facts.add(NarrativeDraft.FactSnapshot.builder()
                                .factId("sum-moment-" + s.getId() + "-" + Integer.toHexString(km.getText().hashCode()))
                                .source("interview")
                                .text(km.getTimestamp() + " · " + km.getText())
                                .subjectId(sidStr)
                                .timestamp(s.getSummary().getGeneratedAt())
                                .build());
                        }
                    }
                }
                // 2) user 原话（M4 暂不引 assistant，避免循环）
                if (s.getMessages() != null) {
                    for (InterviewMessage m : s.getMessages()) {
                        if ("user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                            facts.add(NarrativeDraft.FactSnapshot.builder()
                                .factId("msg-" + s.getId() + "-" + m.hashCode())
                                .source("interview")
                                .text(m.getContent())
                                .subjectId(sidStr)
                                .timestamp(m.getCreatedAt())
                                .build());
                        }
                    }
                }
            }
            // 3) 素材 caption
            List<Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>()
                    .eq(Asset::getSubjectId, sid)
                    .isNotNull(Asset::getCaption)
                    .ne(Asset::getCaption, "")
            );
            for (Asset a : assets) {
                facts.add(NarrativeDraft.FactSnapshot.builder()
                    .factId("cap-" + a.getId())
                    .source("asset_caption")
                    .text(a.getCaption())
                    .subjectId(sidStr)
                    .timestamp(a.getCreatedAt())
                    .build());
            }
            // 4) subject note
            Subject subj = subjectMapper.selectById(sid);
            if (subj != null && subj.getNote() != null && !subj.getNote().isBlank()) {
                facts.add(NarrativeDraft.FactSnapshot.builder()
                    .factId("note-" + sid)
                    .source("note")
                    .text(subj.getNote())
                    .subjectId(sidStr)
                    .timestamp(LocalDateTime.now())
                    .build());
            }
        }
        return facts;
    }

    private NarrativeDraft mustDraft(String draftId) {
        return repo.findById(draftId)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "成稿不存在"));
    }

    private NarrativeDraft.Section findSection(NarrativeDraft d, String sectionId) {
        return d.getSections().stream()
            .filter(s -> sectionId.equals(s.getSectionId()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "章节不存在：" + sectionId));
    }

    private void checkOptimisticLock(NarrativeDraft d, Long ifMatchVersion) {
        if (ifMatchVersion == null) {
            // 没传 If-Match 头就跳过校验（兼容旧调用；前端编辑器必须传）
            return;
        }
        if (!ifMatchVersion.equals(d.getVersion())) {
            throw new BusinessException(ResultCode.CONFLICT,
                "版本不一致：当前=" + d.getVersion() + ", 客户端=" + ifMatchVersion);
        }
    }

    private Project mustProject(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        return p;
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

    private Subject mustSubjectInProject(Long subjectId, Long projectId) {
        Subject s = subjectMapper.selectById(subjectId);
        if (s == null) throw new BusinessException(ResultCode.NOT_FOUND, "人物不存在");
        if (!s.getProjectId().equals(projectId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "人物不属于该项目");
        }
        return s;
    }

    private String mustSubjectDisplayName(Long subjectId) {
        Subject s = subjectMapper.selectById(subjectId);
        if (s == null) throw new BusinessException(ResultCode.NOT_FOUND, "人物不存在");
        return s.getDisplayName();
    }

    private NarrativeDraftVO toVO(NarrativeDraft d) {
        NarrativeDraftVO vo = new NarrativeDraftVO();
        vo.setId(d.getId());
        vo.setProjectId(d.getProjectId());
        vo.setWorkspaceId(d.getWorkspaceId());
        vo.setOwnerId(d.getOwnerId());
        vo.setTemplateId(d.getTemplateId());
        vo.setScope(d.getScope());
        vo.setSubjectIds(d.getSubjectIds());
        vo.setSubjectDisplayNames(d.getSubjectDisplayNames());
        vo.setTitle(d.getTitle());
        vo.setStatus(d.getStatus());
        vo.setSections(toSectionVOs(d.getSections()));
        vo.setFactsSnapshot(toFactVOs(d.getFactsSnapshot()));
        vo.setCreatedAt(d.getCreatedAt());
        vo.setUpdatedAt(d.getUpdatedAt());
        vo.setPublishedAt(d.getPublishedAt());
        vo.setVersion(d.getVersion());
        return vo;
    }

    private List<SectionVO> toSectionVOs(List<NarrativeDraft.Section> sections) {
        if (sections == null) return List.of();
        List<SectionVO> out = new ArrayList<>(sections.size());
        for (NarrativeDraft.Section s : sections) {
            SectionVO vo = new SectionVO();
            vo.setSectionId(s.getSectionId());
            vo.setSectionTitle(s.getSectionTitle());
            vo.setOrder(s.getOrder());
            vo.setTargetCharsMin(s.getTargetCharsMin());
            vo.setTargetCharsMax(s.getTargetCharsMax());
            vo.setMarkPolicy(s.getMarkPolicy());
            vo.setContent(s.getContent());
            vo.setProvenance(s.getProvenance());
            vo.setAiGenerated(s.getAiGenerated());
            vo.setFactsUsed(s.getFactsUsed());
            vo.setLastRewriteStyle(s.getLastRewriteStyle());
            vo.setRewriteCount(s.getRewriteCount());
            vo.setManuallyEditedAt(s.getManuallyEditedAt());
            out.add(vo);
        }
        return out;
    }

    private List<FactSnapshotVO> toFactVOs(List<NarrativeDraft.FactSnapshot> facts) {
        if (facts == null) return List.of();
        List<FactSnapshotVO> out = new ArrayList<>(facts.size());
        for (NarrativeDraft.FactSnapshot f : facts) {
            FactSnapshotVO vo = new FactSnapshotVO();
            vo.setFactId(f.getFactId());
            vo.setSource(f.getSource());
            vo.setText(f.getText());
            vo.setSubjectId(f.getSubjectId());
            vo.setTimestamp(f.getTimestamp());
            out.add(vo);
        }
        return out;
    }
}
