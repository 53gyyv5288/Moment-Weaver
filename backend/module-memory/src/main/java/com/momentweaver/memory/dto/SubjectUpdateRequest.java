package com.momentweaver.memory.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人物局部更新请求。所有字段均可选；JSON 里没出现的字段保持原值不变。
 * 至少要传一个字段（避免空请求），但允许把 relation/note 显式置空串来清空。
 *
 * <p>M14+ 家谱字段更新约定：
 * <ul>
 *   <li>{@code generation = null} → 不变</li>
 *   <li>{@code generation = -50}（INT 最小边界）哨兵 → 清空（置 NULL）</li>
 *   <li>{@code parentSubjectId = null} → 不变</li>
 *   <li>{@code parentSubjectId = -1L}（LONG 哨兵） → 清空（置 NULL）</li>
 *   <li>{@code parentRelationType = null} → 不变；空串 "" → 清空</li>
 * </ul>
 * 用哨兵而非 JsonNullable 是为了避免引入新依赖；负数 generation 在合法范围外（{@code @Min(-50)}）
 * 故可用 -50 作 generation 哨兵。</p>
 */
@Data
public class SubjectUpdateRequest {

    @Size(min = 1, max = 64, message = "姓名 1-64 字")
    private String displayName;

    @Size(max = 32, message = "关系称呼最多 32 字")
    private String relation;

    @Size(max = 512, message = "备注最多 512 字")
    private String note;

    /** M14+ 家族关系图：代际。null=不变；-50=清空。 */
    @Min(value = -50, message = "代际不能小于 -50")
    @Max(value = 50, message = "代际不能大于 50")
    private Integer generation;

    /** M14+ 家族关系图：父/母节点。null=不变；-1L=清空。 */
    private Long parentSubjectId;

    /** M14+ 家族关系图：父/母关系类型。null=不变；空串=清空。 */
    @Pattern(regexp = "^(father|mother|guardian)?$",
        message = "parentRelationType 必须为 father/mother/guardian 之一")
    private String parentRelationType;

    /** 至少传一个字段；都为空 / 全 null 直接 400 拒绝 */
    @AssertTrue(message = "请至少修改一个字段")
    public boolean isAtLeastOnePresent() {
        return displayName != null
            || relation != null
            || note != null
            || generation != null
            || parentSubjectId != null
            || parentRelationType != null;
    }
}
