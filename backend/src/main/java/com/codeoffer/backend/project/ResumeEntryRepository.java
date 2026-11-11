package com.codeoffer.backend.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResumeEntryRepository extends JpaRepository<ResumeEntry, Long> {

    Optional<ResumeEntry> findByProjectId(Long projectId);

    /** 首个注册用户继承存量数据:回填无主行。 */
    @Modifying
    @Query("update ResumeEntry r set r.userId = :uid where r.userId is null")
    int backfillUserId(@Param("uid") Long uid);
}
