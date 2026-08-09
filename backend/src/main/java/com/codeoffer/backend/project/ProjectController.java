package com.codeoffer.backend.project;

import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.project.ApiViews.CardView;
import com.codeoffer.backend.project.ApiViews.CreateRequest;
import com.codeoffer.backend.project.ApiViews.CreateResponse;
import com.codeoffer.backend.project.ApiViews.ProjectDetail;
import com.codeoffer.backend.project.ApiViews.ProjectView;
import com.codeoffer.backend.project.ApiViews.ResumeView;
import com.codeoffer.backend.project.ApiViews.TaskView;
import com.codeoffer.backend.project.GenTask.TaskStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 项目 API:录入(触发生成)、列表(含掌握度徽标)、详情、卡片/简历/面试题、确认入库。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projects;
    private final KnowledgeCardRepository cards;
    private final ResumeEntryRepository resumes;
    private final GenTaskRepository tasks;
    private final GenerateService generate;

    public ProjectController(
            ProjectRepository projects,
            KnowledgeCardRepository cards,
            ResumeEntryRepository resumes,
            GenTaskRepository tasks,
            GenerateService generate) {
        this.projects = projects;
        this.cards = cards;
        this.resumes = resumes;
        this.tasks = tasks;
        this.generate = generate;
    }

    /** 录入仓库 URL → 创建项目与任务 → 异步触发全链路生成。 */
    @PostMapping
    public CreateResponse create(@RequestBody CreateRequest req) {
        String url = req.repoUrl() == null ? "" : req.repoUrl().trim();
        if (url.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoUrl 不能为空");
        }
        Project project = new Project();
        project.setRepoUrl(url);
        project.setName(deriveName(url));
        project = projects.save(project);

        GenTask task = new GenTask();
        task.setProjectId(project.getId());
        task = tasks.save(task);

        generate.runFullGeneration(project.getId(), task.getId());
        return new CreateResponse(project.getId(), task.getId(), project.getStatus().name());
    }

    /** 项目列表(含掌握度徽标聚合)。 */
    @GetMapping
    public List<ProjectView> list() {
        return projects.findAll().stream()
                .map(this::toView)
                .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
                .toList();
    }

    @GetMapping("/{id}")
    public ProjectDetail detail(@PathVariable Long id) {
        Project p = requireProject(id);
        List<CardView> cardViews = cards.findByProjectIdOrderByIdAsc(id).stream()
                .map(c -> new CardView(
                        c.getId(),
                        c.getType().name(),
                        c.getType().getLabel(),
                        c.getTitle(),
                        c.getContent(),
                        c.getAiDraft(),
                        c.isEdited(),
                        c.getMastery(),
                        c.getNextReview()))
                .toList();
        ResumeView resume = resumes.findByProjectId(id)
                .map(r -> new ResumeView(r.getStarContent(), r.getInterviewQas(), r.isEdited()))
                .orElse(null);
        return new ProjectDetail(
                p.getId(),
                p.getName(),
                p.getRepoUrl(),
                p.getDescription(),
                p.getStatus().name(),
                p.getTechStack(),
                p.getHealthReport(),
                cardViews,
                resume);
    }

    /** 最新生成任务(前端轮询进度)。 */
    @GetMapping("/{id}/task")
    public TaskView latestTask(@PathVariable Long id) {
        requireProject(id);
        return tasks.findFirstByProjectIdOrderByIdDesc(id)
                .map(t -> new TaskView(
                        t.getId(),
                        t.getType().name(),
                        t.getStatus().name(),
                        t.getProgress(),
                        t.getErrorMsg()))
                .orElse(null);
    }

    @GetMapping("/{id}/cards")
    public List<CardView> cards(@PathVariable Long id) {
        requireProject(id);
        return cards.findByProjectIdOrderByIdAsc(id).stream()
                .map(c -> new CardView(
                        c.getId(),
                        c.getType().name(),
                        c.getType().getLabel(),
                        c.getTitle(),
                        c.getContent(),
                        c.getAiDraft(),
                        c.isEdited(),
                        c.getMastery(),
                        c.getNextReview()))
                .toList();
    }

    @GetMapping("/{id}/resume")
    public ResumeView resume(@PathVariable Long id) {
        requireProject(id);
        return resumes.findByProjectId(id)
                .map(r -> new ResumeView(r.getStarContent(), r.getInterviewQas(), r.isEdited()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "尚无简历产出"));
    }

    @GetMapping("/{id}/questions")
    public String questions(@PathVariable Long id) {
        requireProject(id);
        return resumes.findByProjectId(id)
                .map(ResumeEntry::getInterviewQas)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "尚无面试题库"));
    }

    /** 用户确认 REVIEW → DONE(成果入库)。 */
    @PostMapping("/{id}/confirm")
    public ProjectDetail confirm(@PathVariable Long id) {
        Project p = requireProject(id);
        if (p.getStatus() == ProjectStatus.REVIEW || p.getStatus() == ProjectStatus.DONE) {
            p.setStatus(ProjectStatus.DONE);
            p.setUpdatedAt(Instant.now());
            projects.save(p);
        }
        return detail(id);
    }

    // ------------------------------------------------------------ helpers

    private Project requireProject(Long id) {
        return projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在: " + id));
    }

    private ProjectView toView(Project p) {
        List<KnowledgeCard> list = cards.findByProjectIdOrderByIdAsc(p.getId());
        long mastered = list.stream().filter(c -> c.getMastery() >= 3).count();
        long due = list.stream()
                .filter(c -> c.getMastery() > 0 && c.getNextReview() != null && c.getNextReview().isBefore(Instant.now()))
                .count();
        return new ProjectView(
                p.getId(),
                p.getName(),
                p.getRepoUrl(),
                p.getStatus().name(),
                (long) list.size(),
                mastered,
                due,
                p.getUpdatedAt());
    }

    private static String deriveName(String repoUrl) {
        String trimmed = repoUrl.endsWith(".git") ? repoUrl.substring(0, repoUrl.length() - 4) : repoUrl;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }
}
