package com.momentweaver.memory.repo;

import com.momentweaver.memory.entity.InterviewSession;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface InterviewSessionRepository extends MongoRepository<InterviewSession, String> {

    List<InterviewSession> findByProjectIdOrderByLastMessageAtDesc(String projectId);

    List<InterviewSession> findBySubjectIdOrderByLastMessageAtDesc(String subjectId);
}
