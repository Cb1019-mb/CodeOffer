package com.codeoffer.backend.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GenTaskRepository extends JpaRepository<GenTask, Long> {

    Optional<GenTask> findFirstByProjectIdOrderByIdDesc(Long projectId);

    List<GenTask> findByProjectIdOrderByIdDesc(Long projectId);

    List<GenTask> findByStatus(GenTask.TaskStatus status);

    /** 首个注册用户继承存量数据:回填无主行。 */
    @Modifying
    @Query("update GenTask t set t.userId = :uid where t.userId is null")
    int backfillUserId(@Param("uid") Long uid);
}
