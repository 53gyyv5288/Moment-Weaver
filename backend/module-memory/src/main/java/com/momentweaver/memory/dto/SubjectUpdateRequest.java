package com.momentweaver.memory.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人物局部更新请求。所有字段均可选；JSON 里没出现的字段保持原值不变。
 * 至少要传一个字段（避免空请求），但允许把 relation/note 显式置空串来清空。
 */
@Data
public class SubjectUpdateRequest {

    @Size(min = 1, max = 64, message = "姓名 1-64 字")
    private String displayName;

    @Size(max = 32, message = "关系称呼最多 32 字")
    private String relation;

    @Size(max = 512, message = "备注最多 512 字")
    private String note;

    /** 至少传一个字段；都为空 / 全 null 直接 400 拒绝 */
    @AssertTrue(message = "请至少修改一个字段")
    public boolean isAtLeastOnePresent() {
        return displayName != null || relation != null || note != null;
    }
}
