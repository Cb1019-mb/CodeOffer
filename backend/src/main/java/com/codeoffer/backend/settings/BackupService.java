package com.codeoffer.backend.settings;

import com.codeoffer.backend.auth.AppUser;
import com.codeoffer.backend.project.ApiViews.CardView;
import com.codeoffer.backend.project.ApiViews.ResumeView;
import com.codeoffer.backend.project.KnowledgeCardRepository;
import com.codeoffer.backend.project.Project;
import com.codeoffer.backend.project.ProjectRepository;
import com.codeoffer.backend.project.ResumeEntryRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** 用户数据备份导出:仅当前用户的项目/卡片/简历。 */
@Service
public class BackupService {

    public record BackupProject(
            Long id, String name, String repoUrl, String status,
            String description, String techStack, String healthReport,
            List<CardView> cards, ResumeView resume) {
    }

    public record Backup(String exportedAt, String username, List<BackupProject> projects) {
    }

    private final ProjectRepository projects;
    private final KnowledgeCardRepository cards;
    private final ResumeEntryRepository resumes;

    public BackupService(ProjectRepository projects, KnowledgeCardRepository cards, ResumeEntryRepository resumes) {
        this.projects = projects;
        this.cards = cards;
        this.resumes = resumes;
    }

    public Backup export(AppUser user) {
        List<BackupProject> list = projects.findAllByUserId(user.getId()).stream()
                .map(p -> new BackupProject(
                        p.getId(), p.getName(), p.getRepoUrl(), p.getStatus().name(),
                        p.getDescription(), p.getTechStack(), p.getHealthReport(),
                        cards.findByProjectIdOrderByIdAsc(p.getId()).stream().map(CardView::from).toList(),
                        resumes.findByProjectId(p.getId()).map(ResumeView::from).orElse(null)))
                .toList();
        return new Backup(Instant.now().toString(), user.getUsername(), list);
    }
}