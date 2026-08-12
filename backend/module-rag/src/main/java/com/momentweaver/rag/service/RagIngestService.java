package com.momentweaver.rag.service;

import com.momentweaver.common.entity.InterviewMessage;
import com.momentweaver.rag.client.RagClient;
import com.momentweaver.rag.dto.AssetSnapshot;
import com.momentweaver.rag.dto.IngestRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Spring 侧业务对象（InterviewMessage / Asset）→ RAG ingest payload，
 * 调 RagClient.ingest 写入 Milvus。
 *
 * <p>为什么放在 module-rag 而不是 module-memory / module-timeline：
 * 保持单向依赖（memory → rag，timeline → rag），不让 rag 反向依赖业务模块。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestService {

    private final RagClient ragClient;

    /**
     * 单 session 的增量 messages → ingest payload。
     * <p>注意：plan §2.3 + §7 「思考链不索引」—— assistant 的 thinking 字段不进入 chunk_text。
     *
     * <p>startTurnIndex：本批 messages 在 session 中的起始 turn 索引（由 InterviewService 计算）。
     * 这样增量 ingest 不会反复覆盖 turn_0，而是写到正确的 turn_N 位置。
     */
    public boolean ingestInterviewSession(String subjectId, String sessionId,
                                          List<InterviewMessage> messages,
                                          int startTurnIndex) {
        if (messages == null || messages.isEmpty()) return false;
        // chunk_id 规则与 AI 端 chunker.interview_chunks 对齐：
        //   interview:{session_id}:turn_{i}，i 累加 (user/assistant 对) 数
        List<IngestRequest.ChunkUpsert> chunks = new ArrayList<>();
        int turnIndex = startTurnIndex;
        for (int i = 0; i < messages.size(); i++) {
            InterviewMessage m = messages.get(i);
            String role = m.getRole();
            String content = m.getContent();
            if (content == null || content.isBlank()) continue;
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            // 只取 user/assistant 两两成对
            if ("user".equals(role)) {
                String userContent = content;
                String assistantContent = "";
                // 看下一条是不是 assistant
                if (i + 1 < messages.size()
                    && "assistant".equals(messages.get(i + 1).getRole())) {
                    InterviewMessage next = messages.get(i + 1);
                    assistantContent = next.getContent() == null ? "" : next.getContent();
                }
                String chunkText = buildChunkText(userContent, assistantContent);
                String parentText = buildParentText(userContent, assistantContent);
                Map<String, Object> md = new HashMap<>();
                md.put("session_id", sessionId);
                md.put("role", "user+assistant");
                md.put("turn_index", turnIndex);
                long ts = m.getCreatedAt() == null
                    ? System.currentTimeMillis()
                    : m.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                md.put("created_at_ms", ts);
                chunks.add(new IngestRequest.ChunkUpsert(
                    "interview:" + sessionId + ":turn_" + turnIndex,
                    "interview:" + sessionId + ":win_" + (turnIndex / 3),
                    chunkText,
                    parentText,
                    subjectId,
                    md
                ));
                turnIndex++;
            }
        }
        if (chunks.isEmpty()) {
            log.debug("ingestInterviewSession: no chunks for session {}", sessionId);
            return false;
        }
        IngestRequest req = new IngestRequest("interview_chunks", false, chunks);
        return ragClient.ingest(req);
    }

    /**
     * 单 Asset → ingest payload。
     * <p>入参用 AssetSnapshot 而非 Asset 实体，避免 module-rag 反向依赖 module-timeline。
     */
    public boolean ingestAsset(AssetSnapshot asset, List<InterviewMessage> linkedMessages) {
        if (asset == null) return false;
        String subjectId = asset.subjectId() == null
            ? null : String.valueOf(asset.subjectId());
        if (subjectId == null || subjectId.isBlank()) {
            log.warn("ingestAsset: asset {} has no subjectId, skip", asset.id());
            return false;
        }
        String caption = asset.caption() == null ? "" : asset.caption();
        String kind = asset.kind() == null ? "image" : asset.kind();
        String fileUrl = asset.ossKey() == null ? "" : asset.ossKey();

        // small chunk：caption + 关联采访片段
        StringBuilder small = new StringBuilder();
        if (!caption.isBlank()) small.append("素材描述：").append(caption).append("\n");
        if (asset.originalName() != null) small.append("文件名：").append(asset.originalName()).append("\n");
        if (linkedMessages != null) {
            int n = Math.min(3, linkedMessages.size());
            for (int i = 0; i < n; i++) {
                InterviewMessage m = linkedMessages.get(i);
                if (m.getContent() == null || m.getContent().isBlank()) continue;
                if ("user".equals(m.getRole())) {
                    small.append("受访者相关回忆：").append(m.getContent()).append("\n");
                }
            }
        }
        String chunkText = small.length() == 0 ? "(无描述)" : small.toString().trim();
        if (chunkText.length() > 2048) chunkText = chunkText.substring(0, 2048);

        // parent chunk：Asset 完整元数据
        StringBuilder parent = new StringBuilder();
        parent.append("素材类型：").append(kind).append("\n");
        if (asset.takenAt() != null) {
            parent.append("拍摄/上传时间：").append(asset.takenAt()).append("\n");
        }
        if (asset.originalName() != null) parent.append("文件名：").append(asset.originalName()).append("\n");
        if (!caption.isBlank()) parent.append("描述：").append(caption).append("\n");
        if (!fileUrl.isBlank()) parent.append("存储路径：").append(fileUrl).append("\n");
        if (linkedMessages != null) {
            int n = Math.min(5, linkedMessages.size());
            for (int i = 0; i < n; i++) {
                InterviewMessage m = linkedMessages.get(i);
                if (m.getContent() == null || m.getContent().isBlank()) continue;
                String who = "user".equals(m.getRole()) ? "受访者" : "AI 采访官";
                parent.append("关联采访（").append(who).append("）：").append(m.getContent()).append("\n");
            }
        }
        String parentText = parent.length() == 0 ? chunkText : parent.toString().trim();
        if (parentText.length() > 16384) parentText = parentText.substring(0, 16384);

        Map<String, Object> md = new HashMap<>();
        md.put("asset_id", asset.id());
        md.put("kind", kind);
        md.put("taken_at", asset.takenAt() == null
            ? 0L
            : asset.takenAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        md.put("file_url", fileUrl);

        IngestRequest.ChunkUpsert chunk = new IngestRequest.ChunkUpsert(
            "asset:" + asset.id() + ":v1",
            "asset:" + asset.id(),
            chunkText,
            parentText,
            subjectId,
            md
        );
        IngestRequest req = new IngestRequest("asset_chunks", false, List.of(chunk));
        return ragClient.ingest(req);
    }

    // ---- helpers ----

    private String buildChunkText(String user, String assistant) {
        StringBuilder sb = new StringBuilder();
        if (user != null && !user.isBlank()) sb.append("受访者：").append(user).append("\n");
        if (assistant != null && !assistant.isBlank()) sb.append("AI 采访官：").append(assistant);
        String s = sb.toString().trim();
        return s.length() > 2048 ? s.substring(0, 2048) : s;
    }

    private String buildParentText(String user, String assistant) {
        // 简化版 parent = chunk + 备注；生产可与 AI 端一致做 3 轮窗口拼接
        return buildChunkText(user, assistant);
    }
}