package com.momentweaver.common;

import lombok.Getter;

/**
 * 错误码规范：
 * 0           成功
 * 1xxx        通用错误
 * 2xxx        账号 / 认证
 * 3xxx        业务（工作区 / 项目 / 人物 / 授权）
 * 4xxx        素材 / 时间线
 * 5xxx        AI / 内容
 * 9xxx        系统
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "ok"),

    // 1xxx 通用
    BAD_REQUEST(1001, "请求参数错误"),
    UNAUTHORIZED(1002, "未登录或登录已过期"),
    FORBIDDEN(1003, "无权限"),
    NOT_FOUND(1004, "资源不存在"),
    METHOD_NOT_ALLOWED(1005, "方法不被允许"),
    CONFLICT(1006, "版本冲突"),

    // 2xxx 账号 / 认证
    USER_NOT_FOUND(2001, "用户不存在"),
    USER_ALREADY_EXISTS(2002, "用户已存在"),
    PASSWORD_INCORRECT(2003, "账号或密码错误"),
    TOKEN_INVALID(2004, "Token 无效"),
    TOKEN_EXPIRED(2005, "Token 已过期"),

    // 3xxx 业务
    WORKSPACE_NOT_FOUND(3001, "工作区不存在"),
    PROJECT_NOT_FOUND(3002, "项目不存在"),
    PROJECT_TYPE_INVALID(3003, "项目类型不合法"),
    SUBJECT_NOT_FOUND(3004, "人物不存在"),
    AUTHORIZATION_NOT_FOUND(3005, "授权记录不存在"),
    AUTHORIZATION_INVALID(3006, "授权已失效"),
    SHARE_TOKEN_INVALID(3007, "分享链接无效"),
    // M5: 分享
    SHARE_LINK_NOT_FOUND(3008, "分享链接不存在"),
    SHARE_LINK_EXPIRED(3009, "分享链接已过期"),
    SHARE_LINK_REVOKED(3010, "分享链接已撤销"),
    SHARE_LINK_PASSWORD_INVALID(3011, "分享链接密码错误"),
    SHARE_LINK_RATE_LIMIT(3012, "访问过于频繁，请稍后再试"),
    SHARE_LINK_DRAFT_NOT_FOUND(3013, "分享链接关联的成稿不存在"),
    // M5: 通知
    NOTIFICATION_NOT_FOUND(3020, "通知不存在"),
    // M5: 导出
    EXPORT_REQUEST_NOT_FOUND(3030, "导出请求不存在"),
    EXPORT_REQUEST_PENDING(3031, "导出正在生成中"),
    EXPORT_REQUEST_FAILED(3032, "导出失败"),
    EXPORT_REQUEST_EXPIRED(3033, "导出链接已过期"),
    // M5: 删除
    DELETION_REQUEST_NOT_FOUND(3040, "删除申请不存在"),
    DELETION_REQUEST_EXPIRED(3041, "已超过恢复期，无法恢复"),
    DELETION_REQUEST_ALREADY_EXECUTED(3042, "删除已执行"),
    DELETION_REQUEST_INVALID_SCOPE(3043, "删除范围不合法"),
    // M5: 合规
    CONSENT_VERSION_REQUIRED(3050, "需要同意当前隐私政策版本"),
    CONSENT_VERSION_OUTDATED(3051, "隐私政策版本已过期"),
    AUDIT_LOG_NOT_FOUND(3060, "审计日志不存在"),
    // M5: PDF
    PDF_GENERATION_FAILED(3070, "PDF 生成失败"),
    PDF_DRAFT_NOT_PUBLISHED(3071, "成稿未发布，无法导出 PDF"),
    PDF_FONT_NOT_FOUND(3072, "PDF 中文字体缺失"),

    // 5xxx AI
    AI_UPSTREAM_ERROR(5001, "AI 服务异常"),
    AI_CONTENT_BLOCKED(5002, "内容被合规系统拦截"),
    AI_PROMPT_TEMPLATE_MISSING(5003, "Prompt 模板缺失"),

    // 9xxx 系统
    SYSTEM_ERROR(9999, "系统异常");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
