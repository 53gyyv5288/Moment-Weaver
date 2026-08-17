package com.momentweaver.memory.listener;

import com.momentweaver.common.event.ProjectCreatedEvent;
import com.momentweaver.memory.service.AuthorizationService;
import com.momentweaver.memory.service.SubjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 个人项目 bootstrap 监听器（M12+）。
 *
 * <p>监听 {@link ProjectCreatedEvent}，仅当 familyId == null 时触发：
 *   1) 调 {@link SubjectService#createForPersonalProject} 创建「我本人」subject
 *   2) 调 {@link AuthorizationService#createSelfGrant} 插入自授权 granted 记录
 *
 * <p>这两步让个人项目「创建即可用」——前端无需走"添加人物 + 发起授权"流程，
 * 直接显示"开始采访"按钮即可。
 *
 * <p>事件解耦原因：module-account 不应反向依赖 module-memory。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalProjectBootstrapListener {

    private final SubjectService subjectService;
    private final AuthorizationService authorizationService;

    @EventListener
    public void onProjectCreated(ProjectCreatedEvent ev) {
        if (ev == null || ev.getProjectId() == null) return;
        // 仅个人项目触发（familyId == null）
        if (ev.getFamilyId() != null) {
            return;
        }
        try {
            String displayName = ev.getUserDisplayName() != null
                ? ev.getUserDisplayName() : "我本人";
            // 1) 创建「我本人」subject
            Long subjectId = subjectService.createForPersonalProject(
                ev.getCreatorId(), ev.getProjectId(), displayName);
            // 2) 插入自授权 granted 记录
            authorizationService.createSelfGrant(
                ev.getCreatorId(), ev.getProjectId(), subjectId);
            log.info("Personal project bootstrap done: projectId={} subjectId={} creator={}",
                ev.getProjectId(), subjectId, ev.getCreatorId());
        } catch (Exception e) {
            // 不抛：避免事件异常回滚项目创建事务
            // （事件是 AFTER_COMMIT 之后发的，但防御性 log 仍然必要）
            log.error("Personal project bootstrap failed: projectId={}, err={}",
                ev.getProjectId(), e.toString(), e);
        }
    }
}
