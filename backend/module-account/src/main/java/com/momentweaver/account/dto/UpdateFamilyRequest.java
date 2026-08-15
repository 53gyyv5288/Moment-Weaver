package com.momentweaver.account.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateFamilyRequest {

    @Size(min = 1, max = 64, message = "家族名 1-64 字")
    private String name;

    @Size(max = 512, message = "描述最多 512 字")
    private String description;

    /** 至少传一个字段；都为空 → 400 */
    @AssertTrue(message = "请至少修改一个字段")
    public boolean isAtLeastOnePresent() {
        return name != null || description != null;
    }
}
