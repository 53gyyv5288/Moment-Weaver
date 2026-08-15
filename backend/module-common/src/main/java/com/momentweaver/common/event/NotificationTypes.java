package com.momentweaver.common.event;

/**
 * 通知事件类型常量（M5）。
 *
 * <p>新事件命名规范：{业务}_{动作}，全大写下划线分隔。
 *
 * <p>前端按 type 决定图标 / 颜色 / 跳转目标。
 */
public final class NotificationTypes {
    private NotificationTypes() {}

    /** draft 发布通知（发到 project 所有成员）。 */
    public static final String DRAFT_PUBLISHED = "DRAFT_PUBLISHED";

    /** 分享链接被创建（发到 share 创建者，作为回执；一般情况下不发，避免噪音）。 */
    public static final String SHARE_CREATED = "SHARE_CREATED";

    /** 分享链接被访问（发到 share 创建者，超过一定次数才发，避免噪音）。 */
    public static final String SHARE_ACCESSED = "SHARE_ACCESSED";

    /** 分享链接被撤销（发到 share 创建者）。 */
    public static final String SHARE_REVOKED = "SHARE_REVOKED";

    /** 分享链接过期（发到 share 创建者）。 */
    public static final String SHARE_EXPIRED = "SHARE_EXPIRED";

    /** 导出数据请求已就绪（发到申请人）。 */
    public static final String EXPORT_READY = "EXPORT_READY";

    /** 删除申请已执行（发到申请人）。 */
    public static final String DELETION_EXECUTED = "DELETION_EXECUTED";

    /** 授权被撤回，draft 章节受影响（发到 project owner）。 */
    public static final String AUTHORIZATION_REVOKED = "AUTHORIZATION_REVOKED";

    /** M11 Phase 2：授权请求已发送给被采访者（发到 subject.linkedUserId）。deepLink = /authz/{token}，一键直达同意页。 */
    public static final String AUTHORIZATION_REQUESTED = "AUTHORIZATION_REQUESTED";

    /** M11 Phase 2：被采访者已同意授权（发到 project owner / 发起授权的人）。 */
    public static final String AUTHORIZATION_GRANTED = "AUTHORIZATION_GRANTED";

    /** 默认类型（兜底，理论上不该出现）。 */
    public static final String UNKNOWN = "UNKNOWN";
}
