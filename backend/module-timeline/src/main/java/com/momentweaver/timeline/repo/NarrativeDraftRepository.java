package com.momentweaver.timeline.repo;

import com.momentweaver.timeline.entity.NarrativeDraft;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface NarrativeDraftRepository extends MongoRepository<NarrativeDraft, String> {

    /** 按项目 + 创建时间倒序 */
    List<NarrativeDraft> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);

    /** 按项目 + scope 筛选 */
    List<NarrativeDraft> findByProjectIdAndScopeOrderByCreatedAtDesc(String projectId, String scope, Pageable pageable);

    /** 按项目 + 状态筛选 */
    List<NarrativeDraft> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status, Pageable pageable);

    /** 按项目 + scope + 状态筛选 */
    List<NarrativeDraft> findByProjectIdAndScopeAndStatusOrderByCreatedAtDesc(
        String projectId, String scope, String status, Pageable pageable);

    /** 计数：项目下某状态的 draft 数 */
    long countByProjectIdAndStatus(String projectId, String status);
}
