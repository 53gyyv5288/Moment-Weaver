package com.momentweaver.memory.dto;

import jakarta.validation.constraints.AssertTrue;
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
