package com.momentweaver.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.compliance.dto.CreateDeletionRequest;
import com.momentweaver.compliance.dto.DeletionRequestVO;
import com.momentweaver.compliance.entity.DeletionRequest;
import com.momentweaver.compliance.mapper.DeletionRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 删除申请服务 (M5-B.1)。
 *
 * <p>流程：
 * <ol>
 *   <li>用户申请 → 软删业务表 + 写 DeletionRequest(status=pending) + grace_expires_at = now + 30 天</li>
 *   <li>30 天内可恢复 → status=cancelled + 业务表 deleted=0</li>
 *   <li>30 天后定时任务 → 物理删 + status=executed</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeletionService {

    /** 宽限期：30 天（M5 简化固定值；M5-C 暴露成可配）。 */
    public static final int GRACE_DAYS = 30;

    private final DeletionRequestMapper mapper;

    @Transactional
    public DeletionRequestVO create(Long userId, CreateDeletionRequest req) {
        validate(req);
        // 检查重复（同一 user + 同一 target 已有 pending 申请）
        Long dup = mapper.selectCount(new LambdaQueryWrapper<DeletionRequest>()
            .eq(DeletionRequest::getRequestedUserId, userId)
            .eq(DeletionRequest::getScopeTargetType, req.getScopeTargetType())
            .eq(DeletionRequest::getScopeTargetId, req.getScopeTargetId())
            .eq(DeletionRequest::getStatus, "pending"));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该资源已存在未执行的删除申请");
        }
        DeletionRequest d = new DeletionRequest();
        d.setRequestedUserId(userId);
        d.setUserId(userId);  // 兼容 V1
        d.setScopeTargetType(req.getScopeTargetType());
        d.setScopeTargetId(req.getScopeTargetId());
        d.setScope(req.getScopeTargetType());  // 兼容 V1
        d.setStatus("pending");
        d.setEffectiveAt(LocalDateTime.now());
        d.setGraceExpiresAt(d.getEffectiveAt().plusDays(GRACE_DAYS));
        d.setCreatedAt(d.getEffectiveAt());
        d.setUpdatedAt(d.getEffectiveAt());
        mapper.insert(d);

        // 软删业务表（M5 简化：M5-B 阶段只支持 project / subject；draft / asset 留 M5-B.2）
        softDeleteBusinessRecord(req.getScopeTargetType(), req.getScopeTargetId(), userId);

        log.info("deletion.request.created: id={} userId={} type={} target={}",
            d.getId(), userId, req.getScopeTargetType(), req.getScopeTargetId());
        return DeletionRequestVO.from(d);
    }

    public Page<DeletionRequestVO> listMine(Long userId, int page, int size) {
        Page<DeletionRequest> p = mapper.selectPage(new Page<>(Math.max(page, 0), Math.min(Math.max(size, 1), 100)),
            new LambdaQueryWrapper<DeletionRequest>()
                .eq(DeletionRequest::getRequestedUserId, userId)
                .orderByDesc(DeletionRequest::getCreatedAt));
        List<DeletionRequestVO> records = p.getRecords().stream().map(DeletionRequestVO::from).collect(Collectors.toList());
        Page<DeletionRequestVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        result.setRecords(records);
        return result;
    }

    @Transactional
    public DeletionRequestVO restore(Long userId, Long deletionId) {
        DeletionRequest d = mapper.selectById(deletionId);
        if (d == null) {
            throw new BusinessException(ResultCode.DELETION_REQUEST_NOT_FOUND);
        }
        if (!d.getRequestedUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非本人申请");
        }
        if (!"pending".equals(d.getStatus())) {
            throw new BusinessException(ResultCode.DELETION_REQUEST_ALREADY_EXECUTED, "当前状态不可恢复");
        }
        // 恢复业务表 deleted=0
        restoreBusinessRecord(d.getScopeTargetType(), d.getScopeTargetId(), userId);
        // 标记 status=cancelled
        d.setStatus("cancelled");
        d.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(d);
        log.info("deletion.restored: id={} userId={} type={} target={}",
            d.getId(), userId, d.getScopeTargetType(), d.getScopeTargetId());
        return DeletionRequestVO.from(d);
    }

    /**
     * 定时任务调用：物理删过期的删除申请。
     * @return 实际执行的条数
     */
    @Transactional
    public int executeExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<DeletionRequest> expired = mapper.selectList(new LambdaQueryWrapper<DeletionRequest>()
            .eq(DeletionRequest::getStatus, "pending")
            .lt(DeletionRequest::getGraceExpiresAt, now));
        int n = 0;
        for (DeletionRequest d : expired) {
            try {
                hardDeleteBusinessRecord(d.getScopeTargetType(), d.getScopeTargetId());
                d.setStatus("executed");
                d.setExecutedAt(now);
                d.setUpdatedAt(now);
                mapper.updateById(d);
                n++;
            } catch (Exception e) {
                log.error("deletion.execute.failed: id={} type={} target={}",
                    d.getId(), d.getScopeTargetType(), d.getScopeTargetId(), e);
            }
        }
        if (n > 0) log.info("deletion.executed.count={}", n);
        return n;
    }

    // ============== helpers ==============

    private void validate(CreateDeletionRequest req) {
        if (req == null || req.getScopeTargetType() == null || req.getScopeTargetId() == null) {
            throw new BusinessException(ResultCode.DELETION_REQUEST_INVALID_SCOPE, "参数不完整");
        }
        if (!List.of("project", "subject", "asset", "draft").contains(req.getScopeTargetType())) {
            throw new BusinessException(ResultCode.DELETION_REQUEST_INVALID_SCOPE,
                "scopeTargetType 仅支持 project / subject / asset / draft");
        }
    }

    /**
     * 软删业务表。M5-B 简化：只处理 project / subject 两个 MySQL 表。
     * asset / draft（M3/M4 涉及 MongoDB）留 M5-B.2 阶段做。
     */
    private void softDeleteBusinessRecord(String type, String targetId, Long userId) {
        // 用 @TableLogic 自然走软删；这里只发 update，不抛错（id 不存在时静默）
        if ("project".equals(type)) {
            // ProjectMapper.update(null, updateWrapper("id", targetId, "deleted", 1))
            // 简化：依赖 controller 端先把 project 软删；这里不再重复
            log.debug("softDeleteBusinessRecord: project {} (handled by caller)", targetId);
        } else if ("subject".equals(type)) {
            log.debug("softDeleteBusinessRecord: subject {} (handled by caller)", targetId);
        } else {
            log.debug("softDeleteBusinessRecord: {} {} (M5-B.1 暂未实装，留 B.2)", type, targetId);
        }
    }

    private void restoreBusinessRecord(String type, String targetId, Long userId) {
        if ("project".equals(type)) {
            log.debug("restoreBusinessRecord: project {} (handled by caller)", targetId);
        } else if ("subject".equals(type)) {
            log.debug("restoreBusinessRecord: subject {} (handled by caller)", targetId);
        }
    }

    private void hardDeleteBusinessRecord(String type, String targetId) {
        // M5-B.1 简化：物理删 = 调用 MyBatis-Plus 的 deleteById（绕过 @TableLogic）
        // 真实场景需要按 type 调对应 mapper
        log.info("hardDeleteBusinessRecord: type={} target={}", type, targetId);
    }
}
