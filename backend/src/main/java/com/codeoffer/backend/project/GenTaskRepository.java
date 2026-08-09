package com.codeoffer.backend.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenTaskRepository extends JpaRepository<GenTask, Long> {

    Optional<GenTask> findFirstByProjectIdOrderByIdDesc(Long projectId);

    List<GenTask> findByProjectIdOrderByIdDesc(Long projectId);
}
