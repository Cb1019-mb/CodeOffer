package com.codeoffer.backend.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeEntryRepository extends JpaRepository<ResumeEntry, Long> {

    Optional<ResumeEntry> findByProjectId(Long projectId);
}
