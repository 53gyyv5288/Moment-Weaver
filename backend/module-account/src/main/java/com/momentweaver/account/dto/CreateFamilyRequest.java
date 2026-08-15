package com.momentweaver.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateFamilyRequest {

    @NotBlank(message = "家族名必填")
    @Size(min = 1, max = 64, message = "家族名 1-64 字")
    private String name;

    @Size(max = 512, message = "描述最多 512 字")
    private String description;
}
