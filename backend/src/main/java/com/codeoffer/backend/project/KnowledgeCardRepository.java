package com.codeoffer.backend.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeCardRepository extends JpaRepository<KnowledgeCard, Long> {

    List<KnowledgeCard> findByProjectIdOrderByIdAsc(Long projectId);

    void deleteByProjectId(Long projectId);

    Optional<KnowledgeCard> findByProjectIdAndType(Long projectId, CardType type);

    /** 首个注册用户继承存量数据:回填无主行。 */
    @Modifying
    @Query("update KnowledgeCard c set c.userId = :uid where c.userId is null")
    int backfillUserId(@Param("uid") Long uid);
}
