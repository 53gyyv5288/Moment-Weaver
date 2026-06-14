package com.momentweaver.memory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SubjectCreateRequest {

    @NotBlank(message = "被采访者姓名不能为空")
    @Size(min = 1, max = 64, message = "姓名 1-64 字")
    private String displayName;

    @Size(max = 32, message = "关系称呼最多 32 字")
    private String relation;

    @Size(max = 512, message = "备注最多 512 字")
    private String note;
}
