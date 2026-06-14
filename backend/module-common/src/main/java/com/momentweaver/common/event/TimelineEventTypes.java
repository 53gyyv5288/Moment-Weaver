package com.momentweaver.common.event;

/**
 * 时间线事件类型常量。供发布者和订阅者共用。
 */
public final class TimelineEventTypes {
    private TimelineEventTypes() {}
    public static final String INTERVIEW_MESSAGE = "interview_message";
    public static final String ASSET_UPLOADED = "asset_uploaded";
    public static final String AI_SUMMARY = "ai_summary";
}