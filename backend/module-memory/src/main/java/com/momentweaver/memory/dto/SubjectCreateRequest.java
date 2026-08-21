package com.momentweaver.memory.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SubjectCreateRequest {

    /**
     * 被采访者姓名。
     * <ul>
     *   <li>匿名路径（familyMemberId 为空）必填，1-64 字</li>
     *   <li>家族成员路径（familyMemberId 非空）不需要传，后端从家族成员里取</li>
     * </ul>
     */
    @Size(max = 64, message = "姓名最多 64 字")
    private String displayName;

    @Size(max = 32, message = "关系称呼最多 32 字")
    private String relation;

    @Size(max = 512, message = "备注最多 512 字")
    private String note;

    /**
     * M11 Phase 2：可选的家族成员 id。
     *
     * <ul>
     *   <li>传非空 → 从家族成员里选被采访者（必须属于项目所属家族），
     *       系统的 displayName/relation 自动从 family_member 取（请求里的值被覆盖）</li>
     *   <li>传 null → 纯匿名被采访者（displayName 必填）</li>
     * </ul>
     */
    private Long familyMemberId;

    /**
     * M14+ 家族关系图：代际。
     * <ul>
     *   <li>正数=长辈（1=父母辈，2=祖辈，3=曾祖辈……）</li>
     *   <li>0=本人辈</li>
     *   <li>负数=晚辈（-1=儿女辈，-2=孙辈……）</li>
     *   <li>null=未分代（合法状态，前端渲染"未分代"灰色区）</li>
     * </ul>
     * 前端通常由 relation 字段自动建议（见前端 GENERATION_HINT 字表）。
     */
    @Min(value = -50, message = "代际不能小于 -50")
    @Max(value = 50, message = "代际不能大于 50")
    private Integer generation;

    /**
     * M14+ 家族关系图：父/母节点 subject.id（同项目内）。
     * <ul>
     *   <li>非空 → 指向同项目的另一个 Subject（业务层校验同 project_id + 防环）</li>
     *   <li>null → 父辈不在本项目（合法状态，家族树根部）</li>
     * </ul>
     */
    private Long parentSubjectId;

    /**
     * M14+ 家族关系图：与父/母的关系类型。
     * <p>MVP 只用 father/mother/guardian；v2 多配偶/复杂关系会扩展。</p>
     */
    @Pattern(regexp = "^(father|mother|guardian)?$",
        message = "parentRelationType 必须为 father/mother/guardian 之一")
    private String parentRelationType;

    /**
     * 自定义校验：
     *   - 家族成员路径：familyMemberId 非空即可，displayName 可空（后端忽略）
     *   - 匿名路径：displayName 必填，familyMemberId 可空
     *
     * 这样 @Valid 不会在 Tab 1 提交时报"姓名不能为空"。
     */
    @AssertTrue(message = "匿名被采访者必须填写姓名")
    public boolean isAnonymousDisplayNameValid() {
        if (familyMemberId != null) {
            // 家族成员路径：displayName 不需要
            return true;
        }
        // 匿名路径：displayName 必填
        return displayName != null && !displayName.isBlank();
    }
}
