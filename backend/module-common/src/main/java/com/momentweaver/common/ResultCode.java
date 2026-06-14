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
