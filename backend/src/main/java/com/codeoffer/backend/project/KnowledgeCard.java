package com.codeoffer.backend.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 知识卡片:保留 AI 草稿(aiDraft)与用户定稿(content),edited 标记防覆盖;
 * mastery/lastReviewed/nextReview 支撑自测与间隔复习(M5)。
 */
@Entity
@Table(name = "knowledge_card")
public class KnowledgeCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    @Enumerated(EnumType.STRING)
    private CardType type;

    private String title;

    /** 用户定稿(未编辑时与 aiDraft 相同) */
    @Column(length = 10000)
    private String content;

    /** AI 原始草稿 */
    @Column(length = 10000)
    private String aiDraft;

    /** 用户补充输入(如对比练习的实现思路,JSON) */
    @Column(length = 10000)
    private String userInput;

    /** 用户是否手动编辑过(编辑后不再被重新生成覆盖) */
    private boolean edited = false;

    /** 掌握度 0-5(0=未开始) */
    private int mastery = 0;

    private Instant lastReviewed;
    private Instant nextReview;

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

    public CardType getType() {
        return type;
    }

    public void setType(CardType type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAiDraft() {
        return aiDraft;
    }

    public void setAiDraft(String aiDraft) {
        this.aiDraft = aiDraft;
    }

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }

    public boolean isEdited() {
        return edited;
    }

    public void setEdited(boolean edited) {
        this.edited = edited;
    }

    public int getMastery() {
        return mastery;
    }

    public void setMastery(int mastery) {
        this.mastery = mastery;
    }

    public Instant getLastReviewed() {
        return lastReviewed;
    }

    public void setLastReviewed(Instant lastReviewed) {
        this.lastReviewed = lastReviewed;
    }

    public Instant getNextReview() {
        return nextReview;
    }

    public void setNextReview(Instant nextReview) {
        this.nextReview = nextReview;
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
