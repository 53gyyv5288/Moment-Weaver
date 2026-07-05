package com.momentweaver.timeline.event;

import com.momentweaver.common.event.AuthorizationRevokedEvent;
import com.momentweaver.common.event.NotificationRequest;
import com.momentweaver.common.event.NotificationTypes;
import com.momentweaver.timeline.entity.NarrativeDraft;
import com.momentweaver.timeline.repo.NarrativeDraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权撤回级联 (M5-B.2)。
 *
 * <p>对引用了被撤回 subject 的 draft：
 * <ul>
 *   <li>把所有 section 的 provenance 改为 system（脱敏）</li>
 *   <li>追加 subjectId 到 withdrawnSubjectIds</li>
 *   <li>记 withdrawnAt</li>
 * </ul>
 * 然后发 AUTHORIZATION_REVOKED 通知给 project owner。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizationRevokedListener {

    private final NarrativeDraftRepository draftRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Async
    @EventListener
    public void onRevoked(AuthorizationRevokedEvent ev) {
        if (ev == null || ev.getProjectId() == null || ev.getSubjectId() == null) return;
        String subjectId = ev.getSubjectId();
        Long projectId = ev.getProjectId();

        // 1) 找出该项目下所有 draft（M5 单项目下 draft 数 < 200，一次拉完）
        List<NarrativeDraft> drafts = draftRepository.findByProjectIdOrderByCreatedAtDesc(
            String.valueOf(projectId), PageRequest.of(0, 500));
        int affectedDrafts = 0;
        int affectedSections = 0;
        for (NarrativeDraft d : drafts) {
            boolean touchDraft = false;
            // subjectIds 直接命中
            boolean subjectHit = d.getSubjectIds() != null && d.getSubjectIds().contains(subjectId);
            // factsSnapshot.subjectId 命中
            boolean factHit = d.getFactsSnapshot() != null && d.getFactsSnapshot().stream()
                .anyMatch(f -> subjectId.equals(f.getSubjectId()));

            if (!subjectHit && !factHit) continue;

            // 把 section 标 system（如果其 factsUsed 涉及该 subject 或者 subjectIds 命中）
            if (d.getSections() != null) {
                for (NarrativeDraft.Section s : d.getSections()) {
                    if (s.getProvenance() != null && "system".equals(s.getProvenance())) continue;
                    boolean sectionHit = subjectHit;
                    if (!sectionHit && s.getFactsUsed() != null) {
                        // 通过 factsUsed 查 subjectId：需要先看 facts 列表
                        if (d.getFactsSnapshot() != null) {
                            Set<String> subjectIdsInFacts = new HashSet<>();
                            for (var f : d.getFactsSnapshot()) {
                                if (s.getFactsUsed().contains(f.getFactId())) {
                                    if (f.getSubjectId() != null) subjectIdsInFacts.add(f.getSubjectId());
                                }
                            }
                            sectionHit = subjectIdsInFacts.contains(subjectId);
                        }
                    }
                    if (sectionHit) {
                        s.setProvenance("system");
                        s.setAiGenerated(false);
                        affectedSections++;
                        touchDraft = true;
                    }
                }
            }

            if (touchDraft) {
                // 追加 withdrawnSubjectIds
                if (d.getWithdrawnSubjectIds() == null) d.setWithdrawnSubjectIds(new java.util.ArrayList<>());
                if (!d.getWithdrawnSubjectIds().contains(subjectId)) {
                    d.getWithdrawnSubjectIds().add(subjectId);
                }
                d.setWithdrawnAt(LocalDateTime.now());
                d.setUpdatedAt(d.getWithdrawnAt());
                draftRepository.save(d);
                affectedDrafts++;
            }
        }

        // 2) 发通知给 project owner
        if (ev.getOwnerId() != null) {
            String subjectName = ev.getSubjectDisplayName() == null ? subjectId : ev.getSubjectDisplayName();
            String title = "授权被撤回";
            String body = String.format("「%s」的授权已撤回", subjectName);
            if (affectedDrafts > 0) {
                body = String.format("「%s」撤回后，%d 篇成稿 / %d 个章节已脱敏",
                    subjectName, affectedDrafts, affectedSections);
            }
            eventPublisher.publishEvent(new NotificationRequest(
                ev.getOwnerId(),
                NotificationTypes.AUTHORIZATION_REVOKED,
                title,
                body,
                String.valueOf(ev.getAuthorizationId()),
                "/projects/" + projectId + "/subjects",
                Map.of(
                    "projectId", projectId,
                    "subjectId", subjectId,
                    "affectedDrafts", affectedDrafts,
                    "affectedSections", affectedSections
                )
            ));
        }
        log.info("auth.revoked.cascade: subjectId={} projectId={} drafts={} sections={}",
            subjectId, projectId, affectedDrafts, affectedSections);
    }
}
