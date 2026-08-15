package com.momentweaver.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank(message = "旧密码必填")
    private String oldPassword;

    @NotBlank(message = "新密码必填")
    @Size(min = 8, max = 64, message = "新密码 8-64 位")
    private String newPassword;
}
