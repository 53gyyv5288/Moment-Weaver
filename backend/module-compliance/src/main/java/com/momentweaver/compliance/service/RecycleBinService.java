package com.momentweaver.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.compliance.dto.RecycleBinItemVO;
import com.momentweaver.compliance.entity.DeletionRequest;
import com.momentweaver.compliance.mapper.DeletionRequestMapper;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.SubjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 回收站服务 (M5-B.2)。
 *
 * <p>从 DeletionRequest + 各业务表（project / subject）的 deleted=1 行聚合出来。
 * M5 简化：只支持 project / subject；asset / draft 留后续。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecycleBinService {

    private static final Set<String> SUPPORTED = Set.of("project", "subject");

    private final DeletionRequestMapper deletionMapper;
    private final ProjectMapper projectMapper;
    private final SubjectMapper subjectMapper;

    public List<RecycleBinItemVO> list(Long userId, String type) {
        List<RecycleBinItemVO> out = new ArrayList<>();
        // 拿所有 pending 状态的删除申请
        List<DeletionRequest> allPending = deletionMapper.selectList(
            new LambdaQueryWrapper<DeletionRequest>()
                .eq(DeletionRequest::getRequestedUserId, userId)
                .eq(DeletionRequest::getStatus, "pending"));
        for (DeletionRequest d : allPending) {
            if (type != null && !type.isBlank() && !type.equals(d.getScopeTargetType())) continue;
            if (!SUPPORTED.contains(d.getScopeTargetType())) continue;
            String title = resolveTitle(d.getScopeTargetType(), d.getScopeTargetId());
            Long daysLeft = null;
            if (d.getGraceExpiresAt() != null) {
                long secs = java.time.Duration.between(LocalDateTime.now(), d.getGraceExpiresAt()).getSeconds();
                daysLeft = Math.max(0, secs / 86400);
            }
            out.add(RecycleBinItemVO.builder()
                .type(d.getScopeTargetType())
                .id(d.getScopeTargetId())
                .title(title)
                .deletedAt(d.getEffectiveAt())
                .daysUntilPermanentDelete(daysLeft)
                .deletionRequestId(d.getId())
                .build());
        }
        return out;
    }

    public void restore(Long userId, String type, String id) {
        if (!SUPPORTED.contains(type)) {
            throw new BusinessException(ResultCode.DELETION_REQUEST_INVALID_SCOPE,
                "M5-B.1 暂仅支持 project / subject 回收");
        }
        // 找对应的 deletion_request
        DeletionRequest d = deletionMapper.selectOne(
            new LambdaQueryWrapper<DeletionRequest>()
                .eq(DeletionRequest::getRequestedUserId, userId)
                .eq(DeletionRequest::getScopeTargetType, type)
                .eq(DeletionRequest::getScopeTargetId, id)
                .eq(DeletionRequest::getStatus, "pending")
                .orderByDesc(DeletionRequest::getCreatedAt)
                .last("LIMIT 1"));
        if (d == null) {
            throw new BusinessException(ResultCode.DELETION_REQUEST_NOT_FOUND, "未找到对应的删除申请");
        }
        // 恢复业务表 deleted=0
        if ("project".equals(type)) {
            Project p = projectMapper.selectById(id);
            if (p == null) {
                throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
            }
            p.setDeleted(0);
            projectMapper.updateById(p);
        } else if ("subject".equals(type)) {
            Subject s = subjectMapper.selectById(id);
            if (s == null) {
                throw new BusinessException(ResultCode.SUBJECT_NOT_FOUND);
            }
            s.setDeleted(0);
            subjectMapper.updateById(s);
        }
        // 标记 deletion_request 已取消
        d.setStatus("cancelled");
        d.setUpdatedAt(LocalDateTime.now());
        deletionMapper.updateById(d);
        log.info("recycle-bin.restored: type={} id={} userId={} drId={}", type, id, userId, d.getId());
    }

    private String resolveTitle(String type, String id) {
        if ("project".equals(type)) {
            Project p = projectMapper.selectById(id);
            return p == null ? "(已删除项目)" : p.getName();
        }
        if ("subject".equals(type)) {
            Subject s = subjectMapper.selectById(id);
            return s == null ? "(已删除人物)" : s.getDisplayName();
        }
        return id;
    }
}
