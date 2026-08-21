package com.momentweaver.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("subject")
public class Subject {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long projectId;
    private String displayName;
    /** 关系称呼，比如 "父亲" "外婆" "我自己" */
    private String relation;
    /** 0=未注册，1=已注册并关联账号 */
    private Integer hasAccount;
    private Long linkedUserId;

    // ============ M14+ 家族关系图 ============
    /** 代际：负数=长辈（-1=父母辈，-2=祖辈），0=本人辈，正数=晚辈（1=儿女辈，2=孙辈）；NULL=未分代 */
    private Integer generation;
    /** 父/母节点 subject.id（同项目内；NULL=父不在项目里） */
    private Long parentSubjectId;
    /** 父/母关系类型：father|mother|guardian；NULL=未指定 */
    private String parentRelationType;
    /**
     * M11 Phase 2：关联的家族成员 id
     * - NULL  = 纯匿名被采访者（老数据 / 一次性 token 授权）
     * - 非空  = 从家族成员里选出来的被采访者
     *
     * 与 linkedUserId 的区别：
     *   - linkedUserId：被采访者本身的 userId（任一用户）
     *   - familyMemberId：被采访者作为「家族成员」在 family_member 表里的 id
     *
     * 两者可能相同也可能不同（家族成员未必注册过）。
     */
    private Long familyMemberId;
    /** 备注：仅 owner 自己可见 */
    private String note;

    // ===== 心声信箱 (Heartcove / Digital Twin) =====
    /** 心声信箱是否已开启。0=未开启；1=已开启 */
    private Integer heartcoveEnabled;
    /** 心声信箱人格摘要缓存（AI 抽取自采访素材；为空时按需生成） */
    private String heartcovePersonaSummary;
    /** 心声信箱开启时间 */
    private LocalDateTime heartcoveEnabledAt;
    /** 启用授权书版本（与 heartcove_consent.consent_version 一致） */
    private String heartcoveConsentVersion;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
