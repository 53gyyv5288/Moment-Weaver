package com.momentweaver.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.momentweaver.memory.entity.Subject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SubjectMapper extends BaseMapper<Subject> {

    /**
     * M14+ 家族级聚合：拉取家族下所有项目的 Subject（跨项目）。
     * SubjectMapper 没有 familyId 字段，但 Project 有；这里通过子查询实现。
     */
    @Select("""
        SELECT s.* FROM `subject` s
        INNER JOIN project p ON s.project_id = p.id
        WHERE p.family_id = #{familyId}
        ORDER BY s.generation IS NULL, s.generation ASC, s.id ASC
        """)
    List<Subject> selectByFamilyId(@Param("familyId") Long familyId);
}
