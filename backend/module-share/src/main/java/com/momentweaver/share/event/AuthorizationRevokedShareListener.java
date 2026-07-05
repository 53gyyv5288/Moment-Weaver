package com.momentweaver.share.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.momentweaver.common.event.AuthorizationRevokedEvent;
import com.momentweaver.share.entity.ShareLink;
import com.momentweaver.share.mapper.ShareLinkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 授权撤回时，自动撤销引用了该 subject 的 share_link (M5-B.2)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizationRevokedShareListener {

    private final ShareLinkMapper shareLinkMapper;

    @Async
    @EventListener
    public void onRevoked(AuthorizationRevokedEvent ev) {
        if (ev == null || ev.getSubjectId() == null || ev.getProjectId() == null) return;
        String subjectId = ev.getSubjectId();
        Long projectId = ev.getProjectId();

        // 找出该项目下所有 active 的 share_link
        List<ShareLink> links = shareLinkMapper.selectList(
            new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getProjectId, projectId)
                .eq(ShareLink::getRevoked, false));
        int revokedCount = 0;
        for (ShareLink l : links) {
            if (l.getSubjectIds() == null || l.getSubjectIds().isBlank()) continue;
            // subjectIds 是逗号分隔字符串
            List<String> ids = Arrays.asList(l.getSubjectIds().split(","));
            if (ids.contains(subjectId)) {
                shareLinkMapper.update(null, new LambdaUpdateWrapper<ShareLink>()
                    .eq(ShareLink::getId, l.getId())
                    .set(ShareLink::getRevoked, true));
                revokedCount++;
            }
        }
        log.info("auth.revoked.share.cascade: subjectId={} projectId={} revokedShares={}",
            subjectId, projectId, revokedCount);
    }
}
