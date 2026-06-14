package com.momentweaver.timeline.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.PageResult;
import com.momentweaver.common.ResultCode;
import com.momentweaver.timeline.dto.TimelineItemVO;
import com.momentweaver.timeline.entity.TimelineEvent;
import com.momentweaver.timeline.repo.TimelineEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 时间线服务：写入 + 查询。
 *
 * <p>写入由其他服务（InterviewService / AssetService）在事件触发时调用。
 * 单一职责：本服务不感知业务规则，只负责存储 + 拼装 VO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineService {

    private final TimelineEventRepository repo;
    private final ProjectMapper projectMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;

    public void record(String projectId, String subjectId, String type,
                       String refId, String title, String preview, Map<String, Object> metadata) {
        try {
            TimelineEvent e = TimelineEvent.builder()
                .projectId(projectId)
                .subjectId(subjectId)
                .type(type)
                .eventAt(LocalDateTime.now())
                .refId(refId)
                .title(title)
                .preview(preview)
                .metadata(metadata)
                .build();
            repo.save(e);
        } catch (Exception ex) {
            // 时间线是「增强」，不能因为写时间线挂掉阻塞主业务
            log.warn("Failed to write timeline event (type={}, refId={}): {}", type, refId, ex.getMessage());
        }
    }

    public PageResult<TimelineItemVO> query(Long userId, Long projectId, String subjectId, String type,
                                            LocalDateTime from, LocalDateTime to,
                                            int page, int size) {
        Project p = mustProject(projectId);
        ensureMember(p.getWorkspaceId(), userId);

        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), Math.min(Math.max(1, size), 200));

        List<TimelineEvent> events;
        if (subjectId != null && type != null) {
            events = repo.findByProjectIdAndSubjectIdAndTypeOrderByEventAtDesc(String.valueOf(projectId), subjectId, type, pageable);
        } else if (subjectId != null) {
            events = repo.findByProjectIdAndSubjectIdOrderByEventAtDesc(String.valueOf(projectId), subjectId, pageable);
        } else if (type != null) {
            events = repo.findByProjectIdAndTypeOrderByEventAtDesc(String.valueOf(projectId), type, pageable);
        } else if (from != null && to != null) {
            events = repo.findInRange(String.valueOf(projectId), from, to, pageable);
        } else {
            events = repo.findByProjectIdOrderByEventAtDesc(String.valueOf(projectId), pageable);
        }

        long total = events.size(); // 取当前页条数（Mongo count 单独查询成本高，M3 简化处理）
        return new PageResult<>(total, page, size, events.stream().map(this::toVO).toList());
    }

    private TimelineItemVO toVO(TimelineEvent e) {
        TimelineItemVO vo = new TimelineItemVO();
        vo.setId(e.getId());
        vo.setProjectId(e.getProjectId());
        vo.setSubjectId(e.getSubjectId());
        vo.setType(e.getType());
        vo.setEventAt(e.getEventAt());
        vo.setRefId(e.getRefId());
        vo.setTitle(e.getTitle());
        vo.setPreview(e.getPreview());
        vo.setMetadata(e.getMetadata());
        return vo;
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
}