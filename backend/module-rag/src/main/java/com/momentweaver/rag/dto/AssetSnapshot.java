package com.momentweaver.rag.dto;

import java.time.LocalDateTime;

/**
 * Asset 数据快照（用于跨模块事件传递）。
 *
 * <p>为什么不用 Asset 实体直接传：
 * Asset 在 module-timeline.entity 包，含 @TableName 等 MyBatis 注解。
 * 让 module-rag 直接 import Asset 会形成 timeline → memory → rag → timeline 循环依赖。
 *
 * <p>所以 AssetService 在发布 AssetUpsertedEvent 前，先映射成这个 POJO；
 * RagIngestService 只看 snapshot，不依赖 Asset 实体。
 */
public record AssetSnapshot(
    Long id,
    Long subjectId,
    String kind,            // image | audio | video
    String caption,
    String originalName,
    String ossKey,
    LocalDateTime takenAt
) {}
