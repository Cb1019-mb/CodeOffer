package com.codeoffer.backend.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 简历条目:STAR 四段(JSON)与面试问答库(JSON),保留 AI 草稿。
 */
@Entity
@Table(name = "resume_entry")
public class ResumeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private Long projectId;

    /** JSON:{situation,task,action,result,summary} */
    @Column(length = 10000)
    private String starContent;

    /** JSON:[{level,question,answer}] */
    @Column(length = 20000)
    private String interviewQas;

    /** AI 原始草稿(JSON 全量) */
    @Column(length = 20000)
    private String aiDraft;

    private boolean edited = false;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getStarContent() {
        return starContent;
    }

    public void setStarContent(String starContent) {
        this.starContent = starContent;
    }

    public String getInterviewQas() {
        return interviewQas;
    }

    public void setInterviewQas(String interviewQas) {
        this.interviewQas = interviewQas;
    }

    public String getAiDraft() {
        return aiDraft;
    }

    public void setAiDraft(String aiDraft) {
        this.aiDraft = aiDraft;
    }

    public boolean isEdited() {
        return edited;
    }

    public void setEdited(boolean edited) {
        this.edited = edited;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
