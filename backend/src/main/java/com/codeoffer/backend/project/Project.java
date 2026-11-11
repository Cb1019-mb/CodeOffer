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
 * 一个 GitHub 仓库为一个项目;JSON 字段以字符串存储(MySQL/H2 的文本列)。
 */
@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户;存量数据为 null,首个注册用户注册时回填。 */
    @Column(name = "user_id")
    private Long userId;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    @Column(nullable = false)
    private String repoUrl;

    private String name;

    @Column(length = 2000)
    private String description;

    /** JSON 数组:tech stack */
    @Column(length = 4000)
    private String techStack;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status = ProjectStatus.PENDING;

    /** 体检报告 JSON(可选) */
    @Column(length = 4000)
    private String healthReport;

    /** Python 解析结果 JSON(供重新生成时复用);大仓库解析树可能很大,不设实际长度上限 */
    @Column(length = 1_000_000)
    private String repoInfo;

    /** Python 克隆返回的本地目录 */
    private String repoDir;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTechStack() {
        return techStack;
    }

    public void setTechStack(String techStack) {
        this.techStack = techStack;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectStatus status) {
        this.status = status;
    }

    public String getHealthReport() {
        return healthReport;
    }

    public void setHealthReport(String healthReport) {
        this.healthReport = healthReport;
    }

    public String getRepoInfo() {
        return repoInfo;
    }

    public void setRepoInfo(String repoInfo) {
        this.repoInfo = repoInfo;
    }

    public String getRepoDir() {
        return repoDir;
    }

    public void setRepoDir(String repoDir) {
        this.repoDir = repoDir;
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
