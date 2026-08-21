package com.momentweaver.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家族成员。
 *
 * <p>角色：
 *   <ul>
 *     <li>admin  —— 家族管理员（创建者），可邀请/移除成员、改家族名/描述</li>
 *     <li>editor —— 家族编辑者，能在家族下创建/编辑项目、采访、成稿</li>
 *     <li>viewer —— 家族旁观者，只读（适合不想误操作的家属）</li>
 *   </ul>
 */
@Data
@TableName("family_member")
public class FamilyMember {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long familyId;
    private Long userId;

    /** admin | editor | viewer */
    private String role;

    // ============ M14+ 家族关系图（家谱节点源头） ============
    /** 代际：负数=长辈（-1=父母辈，-2=祖辈），0=本人辈，正数=晚辈（1=儿女辈，2=孙辈）；NULL=未分代 */
    private Integer generation;
    /** 上一代 family_member.id（同家族内；NULL=上一代不在家族里） */
    private Long parentFamilyMemberId;
    /** 与上一代的关系类型：father|mother|guardian；NULL=未指定 */
    private String parentMemberRelationType;

    private LocalDateTime joinedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
