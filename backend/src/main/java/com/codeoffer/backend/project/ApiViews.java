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
            String userInput,
            boolean edited,
            int mastery,
            Instant nextReview) {

        public static CardView from(KnowledgeCard c) {
            return new CardView(
                    c.getId(),
                    c.getType().name(),
                    c.getType().getLabel(),
                    c.getTitle(),
                    c.getContent(),
                    c.getAiDraft(),
                    c.getUserInput(),
                    c.isEdited(),
                    c.getMastery(),
                    c.getNextReview());
        }
    }

    public record ResumeView(String starContent, String interviewQas, boolean edited) {

        public static ResumeView from(ResumeEntry r) {
            return new ResumeView(r.getStarContent(), r.getInterviewQas(), r.isEdited());
        }
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
