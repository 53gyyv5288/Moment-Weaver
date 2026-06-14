package com.momentweaver.common;

import lombok.Getter;

/**
 * 业务异常。Service 层抛出，由 GlobalExceptionHandler 捕获并转成 Result 响应。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ResultCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
    }

    public BusinessException(ResultCode rc, String detail) {
        super(rc.getMessage() + ": " + detail);
        this.code = rc.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
