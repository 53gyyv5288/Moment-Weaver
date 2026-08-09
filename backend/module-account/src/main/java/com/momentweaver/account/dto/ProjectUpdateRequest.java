package com.momentweaver.account.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 项目局部更新请求。所有字段均可选；JSON 里没出现的字段保持原值不变。
 * 至少要传一个字段（避免空请求）；description 显式传 "" 视为清空。
 */
@Data
public class ProjectUpdateRequest {

    @Size(min = 1, max = 128, message = "项目名 1-128 字")
    private String name;

    @Size(max = 512, message = "描述最多 512 字")
    private String description;

    /** 至少传一个字段；都为 null 直接 400 拒绝 */
    @AssertTrue(message = "请至少修改一个字段")
    public boolean isAtLeastOnePresent() {
        return name != null || description != null;
    }
}
