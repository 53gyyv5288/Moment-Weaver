package com.momentweaver.timeline.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FactSnapshotVO {

    private String factId;
    /** interview | asset_caption | note */
    private String source;
    private String text;

    @JsonSerialize(using = ToStringSerializer.class)
    private String subjectId;

    private LocalDateTime timestamp;
}
