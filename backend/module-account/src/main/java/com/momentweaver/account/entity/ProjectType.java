package com.momentweaver.account.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目类型。一期：family | personal；team 二期。
 */
@Getter
@AllArgsConstructor
public enum ProjectType {

    FAMILY("family", "家族"),
    PERSONAL("personal", "个人");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    public static ProjectType of(String code) {
        for (ProjectType t : values()) {
            if (t.code.equalsIgnoreCase(code)) return t;
        }
        throw new IllegalArgumentException("未知项目类型: " + code);
    }
}
