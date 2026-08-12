package com.momentweaver.timeline.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.PageResult;
import com.momentweaver.common.Result;
import com.momentweaver.common.ResultCode;
import com.momentweaver.rag.client.RagClient;
import com.momentweaver.rag.dto.EvidenceChunk;
import com.momentweaver.rag.dto.SearchRequest;
import com.momentweaver.timeline.dto.TimelineItemVO;
import com.momentweaver.timeline.dto.TimelineSearchVO;
import com.momentweaver.timeline.entity.Asset;
import com.momentweaver.timeline.mapper.AssetMapper;
import com.momentweaver.timeline.service.TimelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "时间线 / Timeline")
@RestController
@RequiredArgsConstructor
public class TimelineController {

    private final TimelineService timelineService;
    private final RagClient ragClient;
    private final ProjectMapper projectMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final AssetMapper assetMapper;

    @GetMapping("/api/v1/projects/{pid}/timeline")
    @Operation(summary = "项目时间线（按人物/类型/时间筛选）")
    public Result<PageResult<TimelineItemVO>> query(
        @PathVariable("pid") Long pid,
        @RequestParam(required = false) String subjectId,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "30") int size) {
        return Result.ok(timelineService.query(
            CurrentUser.requireId(), pid, subjectId, type, from, to, page, size
        ));
    }

    /**
     * 语义搜索素材（plan §4.3.B）。
     *
     * <p>GET /api/v1/projects/{pid}/timeline/search?q=1980年春节&subjectId=...
     *
     * <p>走 RAG：调 FastAPI hybrid_search + reranker；按 asset_id 回查 Asset
     * 拿 URL / takenAt 等展示字段。前端可复用 TimelineItemVO 渲染。
     */
    @GetMapping("/api/v1/projects/{pid}/timeline/search")
    @Operation(summary = "时间线素材语义搜索（基于 RAG）")
    public Result<List<TimelineSearchVO>> search(
        @PathVariable("pid") Long pid,
        @RequestParam("q") String query,
        @RequestParam("subjectId") String subjectId,
        @RequestParam(defaultValue = "10") int limit) {
        Long userId = CurrentUser.requireId();
        Project p = mustProject(pid);
        ensureMember(p.getWorkspaceId(), userId);
        if (query == null || query.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "q 不能为空");
        }
        if (subjectId == null || subjectId.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "subjectId 必填");
        }

        // 调 RAG search
        List<EvidenceChunk> chunks = ragClient.searchEvidence(
            SearchRequest.SCENARIO_TIMELINE, query, subjectId, userId);
        if (chunks == null || chunks.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 收集 asset_id 批量查 Asset
        List<Long> assetIds = chunks.stream()
            .map(EvidenceChunk::assetId)
            .filter(java.util.Objects::nonNull)
            .map(o -> {
                if (o instanceof Number n) return n.longValue();
                try { return Long.parseLong(String.valueOf(o)); }
                catch (Exception e) { return null; }
            })
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, Asset> assetMap = assetIds.isEmpty() ? Collections.emptyMap() :
            assetMapper.selectBatchIds(assetIds).stream()
                .collect(java.util.stream.Collectors.toMap(Asset::getId, a -> a));

        // 拼装 VO
        List<TimelineSearchVO> out = new java.util.ArrayList<>();
        for (EvidenceChunk c : chunks) {
            TimelineSearchVO vo = new TimelineSearchVO();
            vo.setChunkId(c.chunkId());
            vo.setSubjectId(subjectId);
            vo.setProjectId(String.valueOf(pid));
            vo.setScore(c.score());
            vo.setMetadata(c.metadata());
            Object assetId = c.assetId();
            if (assetId != null) {
                Long aid = assetId instanceof Number n ? n.longValue()
                    : Long.parseLong(String.valueOf(assetId));
                vo.setRefId(String.valueOf(aid));
                Asset a = assetMap.get(aid);
                if (a != null) {
                    vo.setKind(a.getKind());
                    vo.setTakenAt(a.getTakenAt());
                    vo.setUrl(buildUrl(a));
                }
            }
            String parent = c.parentText();
            String preview = parent == null ? "" : parent;
            if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
            vo.setPreview(preview);
            out.add(vo);
            if (out.size() >= limit) break;
        }
        log.info("Timeline semantic search: pid={} q={} subject={} n={}",
            pid, abbreviate(query, 30), subjectId, out.size());
        return Result.ok(out);
    }

    private String buildUrl(Asset a) {
        if (a.getStorage() == null || "local".equals(a.getStorage())) {
            return "/api/v1/assets/" + a.getId() + "/file";
        }
        // real OSS：第一版返回公开读 URL；生产应换签名 URL
        return "https://" + a.getOssBucket() + "." + a.getOssRegion() + ".aliyuncs.com/" + a.getOssKey();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
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