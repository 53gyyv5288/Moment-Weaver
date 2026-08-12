package com.momentweaver.rag.event;

import com.momentweaver.common.entity.InterviewMessage;
import com.momentweaver.rag.dto.AssetSnapshot;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * Asset 上传 / 修改事件（AFTER_COMMIT 后发布）。
 *
 * <p>由 AssetService.upload/update 发布；RagIngestListener 收到后切 chunk → ingest。
 *
 * <p>注意：事件只携带 AssetSnapshot（不含 Asset 实体），避免 module-rag 反向依赖 module-timeline。
 */
@Getter
public class AssetUpsertedEvent extends ApplicationEvent {

    private final AssetSnapshot asset;
    /** 关联采访片段（caption 关联 user 原话，可选；为空也能 ingest）。 */
    private final List<InterviewMessage> linkedMessages;

    public AssetUpsertedEvent(Object source, AssetSnapshot asset,
                              List<InterviewMessage> linkedMessages) {
        super(source);
        this.asset = asset;
        this.linkedMessages = linkedMessages == null ? List.of() : List.copyOf(linkedMessages);
    }
}
