package com.codeoffer.backend.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeCardRepository extends JpaRepository<KnowledgeCard, Long> {

    List<KnowledgeCard> findByProjectIdOrderByIdAsc(Long projectId);

    long countByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);

    Optional<KnowledgeCard> findByProjectIdAndType(Long projectId, CardType type);
}
