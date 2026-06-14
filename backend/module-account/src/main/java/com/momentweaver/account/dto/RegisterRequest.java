package com.momentweaver.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "手机号或邮箱必填")
    private String identifier; // phone 或 email

    @NotBlank(message = "密码必填")
    @Size(min = 8, max = 64, message = "密码长度需在 8-64 之间")
    private String password;

    /** 可选，默认随机生成 */
    private String displayName;

    /** 前端兼容字段：account 写入 identifier */
    public void setAccount(String account) {
        this.identifier = account;
    }
}
