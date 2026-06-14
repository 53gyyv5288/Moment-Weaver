package com.momentweaver.memory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InterviewSendRequest {

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "单条消息最多 4000 字")
    private String content;
}
