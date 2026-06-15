package com.momentweaver.common.event;

/**
 * 时间线事件类型常量。供发布者和订阅者共用。
 */
public final class TimelineEventTypes {
    private TimelineEventTypes() {}
    public static final String INTERVIEW_MESSAGE = "interview_message";
    public static final String ASSET_UPLOADED = "asset_uploaded";
    public static final String AI_SUMMARY = "ai_summary";
    // M4 成稿相关
    public static final String NARRATIVE_DRAFT_CREATED = "narrative_draft_created";
    public static final String NARRATIVE_DRAFT_SECTION_EDITED = "narrative_draft_section_edited";
    public static final String NARRATIVE_DRAFT_PUBLISHED = "narrative_draft_published";
}