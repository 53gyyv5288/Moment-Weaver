package com.momentweaver.memory.dto;

import lombok.Data;

/**
 * M14+ 家族关系图：树节点（项目级 / 家族级）。
 *
 * <p>扁平结构（前端 d3.stratify 自建树），便于换可视化库不用改后端。</p>
 *
 * <p><b>id / parentSubjectId 用 String 类型</b>：项目级时是 Subject.id（Long），
 * 家族级时是 "fm-{familyMemberId}" 或 "sub-{subjectId}" 字符串（合并去重用）——
 * 用 String 兼容两种场景。</p>
 */
@Data
public class SubjectTreeNodeVO {

    /** 节点 id：项目级 = Subject.id；家族级 = "fm-{familyMemberId}" 或 "sub-{subjectId}" */
    private String id;

    private String displayName;
    private String relation;

    /** 代际；NULL=未分代（进"待归位"灰色区）。语义：正数=晚辈，0=本人，负数=长辈 */
    private Integer generation;

    /** 父节点 id；NULL=根节点；项目级 = Subject.id，家族级可能跨 family 不指向树内节点 */
    private String parentSubjectId;

    private String parentRelationType;

    /** 派生：generation 一致性警告文案；NULL/空=一致 */
    private String generationWarning;

    /** 关联家族成员 id（NULL=匿名） */
    private String familyMemberId;
}
