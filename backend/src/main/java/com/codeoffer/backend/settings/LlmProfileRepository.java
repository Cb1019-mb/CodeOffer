package com.codeoffer.backend.settings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmProfileRepository extends JpaRepository<LlmProfile, Long> {

    List<LlmProfile> findByUserIdOrderByIdAsc(Long userId);

    Optional<LlmProfile> findByIdAndUserId(Long id, Long userId);
}