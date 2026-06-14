package com.momentweaver.timeline.repo;

import com.momentweaver.timeline.entity.TimelineEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface TimelineEventRepository extends MongoRepository<TimelineEvent, String> {

    /** 按项目 + 时间倒序（通用） */
    List<TimelineEvent> findByProjectIdOrderByEventAtDesc(String projectId, Pageable pageable);

    /** 按项目 + 人物 */
    List<TimelineEvent> findByProjectIdAndSubjectIdOrderByEventAtDesc(String projectId, String subjectId, Pageable pageable);

    /** 按项目 + 类型 */
    List<TimelineEvent> findByProjectIdAndTypeOrderByEventAtDesc(String projectId, String type, Pageable pageable);

    /** 按项目 + 人物 + 类型 */
    List<TimelineEvent> findByProjectIdAndSubjectIdAndTypeOrderByEventAtDesc(String projectId, String subjectId, String type, Pageable pageable);

    /** 计数：按项目 + 时间区间 */
    @Query("{ 'projectId': ?0, 'eventAt': { $gte: ?1, $lte: ?2 } }")
    List<TimelineEvent> findInRange(String projectId, LocalDateTime from, LocalDateTime to, Pageable pageable);
}