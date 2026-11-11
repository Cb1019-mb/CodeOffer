package com.codeoffer.backend.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByRepoUrl(String repoUrl);

    Optional<Project> findByIdAndUserId(Long id, Long userId);

    Optional<Project> findByRepoUrlAndUserId(String repoUrl, Long userId);

    java.util.List<Project> findAllByUserId(Long userId);

    /** 首个注册用户继承存量数据:回填无主行。 */
    @Modifying
    @Query("update Project p set p.userId = :uid where p.userId is null")
    int backfillUserId(@Param("uid") Long uid);
}
