package com.momentweaver.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 项目创建事件。
 *
 * <p>由 {@code ProjectService.create()} 发布（AFTER_COMMIT）。
 * 监听方：
 * <ul>
 *   <li>module-memory：familyId == null 的个人项目 → 自动创建「我本人」subject + 自授权 granted 记录
 *       （PersonalProjectBootstrapListener）</li>
 * </ul>
 *
 * <p>设计：用事件解耦避免 module-account → module-memory 的反向依赖。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectCreatedEvent {
    /** 创建者 userId（= project.owner_id） */
    private Long creatorId;
    /** 项目 id */
    private Long projectId;
    /** 家族 id；NULL=个人项目 */
    private Long familyId;
    /** 用户昵称（个人项目 bootstrap 时用作 subject.displayName） */
    private String userDisplayName;
}
