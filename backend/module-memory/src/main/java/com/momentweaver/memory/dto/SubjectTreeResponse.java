package com.momentweaver.memory.dto;

import lombok.Data;

import java.util.List;

/**
 * M14+ 家族关系图：项目级聚合响应。
 *
 * <p>{@code nodes} 是所有 subject 的扁平列表（含根、未分代、断链等），前端用
 * {@code d3.stratify().id(d => d.id).parentId(d => d.parentSubjectId ?? '__virtual__')}
 * 自建树。换可视化库时不用改后端。</p>
 *
 * <p>{@code warnings} 是 generation 不一致的 subject id 列表，前端 FamilyDetail 顶部
 * 显示 el-alert + 可跳转修正。</p>
 */
@Data
public class SubjectTreeResponse {

    /** 所有 subject 扁平数组（含根、未分代、孤儿） */
    private List<SubjectTreeNodeVO> nodes;

    /** 待归位 subject id 列表（generation=null 或 parent 指向不存在节点的节点） */
    private List<Long> orphans;

    /** generation 不一致的 subject id 列表（与父节点 generation 不匹配） */
    private List<Long> warnings;

    /** 节点总数（= nodes.size()） */
    private Integer total;
}
