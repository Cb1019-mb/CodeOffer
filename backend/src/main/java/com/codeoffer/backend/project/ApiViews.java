package com.codeoffer.backend.project;

import java.time.Instant;
import java.util.List;

/**
 * API 视图 DTO(record)。
 */
public final class ApiViews {

    private ApiViews() {
    }

    public record CreateRequest(String repoUrl) {
    }

    public record CreateResponse(Long projectId, Long taskId, String status) {
    }

    public record TaskView(Long id, String type, String status, Integer progress, String errorMsg) {
    }

    public record CardView(
            Long id,
            String type,
            String typeLabel,
            String title,
            String content,
            String aiDraft,
            boolean edited,
            int mastery,
            Instant nextReview) {
    }

    public record ResumeView(String starContent, String interviewQas, boolean edited) {
    }

    /** 列表项:含掌握度徽标聚合数据(N7 浅做) */
    public record ProjectView(
            Long id,
            String name,
            String repoUrl,
            String status,
            Long cardCount,
            Long mastered,
            Long due,
            Instant updatedAt) {
    }

    public record ProjectDetail(
            Long id,
            String name,
            String repoUrl,
            String description,
            String status,
            String techStack,
            String healthReport,
            List<CardView> cards,
            ResumeView resume) {
    }
}
