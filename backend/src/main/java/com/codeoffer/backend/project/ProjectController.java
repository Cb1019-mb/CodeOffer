package com.codeoffer.backend.project;

import com.codeoffer.backend.auth.CurrentUser;
import com.codeoffer.backend.project.ApiViews.CardView;
import com.codeoffer.backend.project.ApiViews.CreateRequest;
import com.codeoffer.backend.project.ApiViews.CreateResponse;
import com.codeoffer.backend.project.ApiViews.ProjectDetail;
import com.codeoffer.backend.project.ApiViews.ProjectView;
import com.codeoffer.backend.project.ApiViews.ResumeView;
import com.codeoffer.backend.project.ApiViews.TaskView;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目 API:仅路由与参数接收,业务逻辑全部在
 * ProjectService / CardService / ResumeService,数据按当前用户隔离。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final CardService cardService;
    private final ResumeService resumeService;
    private final CurrentUser currentUser;

    public ProjectController(
            ProjectService projectService,
            CardService cardService,
            ResumeService resumeService,
            CurrentUser currentUser) {
        this.projectService = projectService;
        this.cardService = cardService;
        this.resumeService = resumeService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public CreateResponse create(@RequestBody CreateRequest req) {
        return projectService.create(currentUser.require().getId(), req);
    }

    @GetMapping
    public List<ProjectView> list() {
        return projectService.list(currentUser.require().getId());
    }

    @GetMapping("/{id}")
    public ProjectDetail detail(@PathVariable Long id) {
        return projectService.detail(currentUser.require().getId(), id);
    }

    @GetMapping("/{id}/task")
    public TaskView latestTask(@PathVariable Long id) {
        return projectService.latestTask(currentUser.require().getId(), id);
    }

    @GetMapping("/{id}/cards")
    public List<CardView> cards(@PathVariable Long id) {
        return projectService.cards(currentUser.require().getId(), id);
    }

    @GetMapping("/{id}/resume")
    public ResumeView resume(@PathVariable Long id) {
        return projectService.resume(currentUser.require().getId(), id);
    }

    @PostMapping("/{id}/confirm")
    public ProjectDetail confirm(@PathVariable Long id) {
        return projectService.confirm(currentUser.require().getId(), id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        projectService.delete(currentUser.require().getId(), id);
    }

    @PostMapping("/{id}/regenerate")
    public CreateResponse regenerate(@PathVariable Long id) {
        return projectService.regenerate(currentUser.require().getId(), id);
    }

    // ------------------------------------------------------------ 卡片域

    public record EditContentRequest(String content) {
    }

    public record UserInputRequest(String input) {
    }

    public record ReviewRequest(boolean known) {
    }

    @PutMapping("/{id}/cards/{cardId}/content")
    public CardView editCard(@PathVariable Long id, @PathVariable Long cardId, @RequestBody EditContentRequest req) {
        return cardService.editCard(currentUser.require().getId(), id, cardId, req.content());
    }

    @PostMapping("/{id}/cards/{cardId}/adopt")
    public CardView adoptDraft(@PathVariable Long id, @PathVariable Long cardId) {
        return cardService.adoptDraft(currentUser.require().getId(), id, cardId);
    }

    @PostMapping("/{id}/cards/{cardId}/regenerate")
    public CardView regenerateCard(@PathVariable Long id, @PathVariable Long cardId) {
        return cardService.regenerateCard(currentUser.require().getId(), id, cardId);
    }

    @PostMapping("/{id}/cards/{cardId}/user-input")
    public CardView addUserInput(@PathVariable Long id, @PathVariable Long cardId, @RequestBody UserInputRequest req) {
        return cardService.addUserInput(currentUser.require().getId(), id, cardId, req.input());
    }

    @PostMapping("/{id}/cards/{cardId}/review")
    public CardView reviewCard(@PathVariable Long id, @PathVariable Long cardId, @RequestBody ReviewRequest req) {
        return cardService.reviewCard(currentUser.require().getId(), id, cardId, req.known());
    }

    @GetMapping("/{id}/review-queue")
    public List<CardView> reviewQueue(@PathVariable Long id) {
        return cardService.reviewQueue(currentUser.require().getId(), id);
    }

    // ------------------------------------------------------------ 简历域

    public record EditStarRequest(String starContent) {
    }

    @PutMapping("/{id}/resume/star")
    public ResumeView editResume(@PathVariable Long id, @RequestBody EditStarRequest req) {
        return resumeService.editStar(currentUser.require().getId(), id, req.starContent());
    }

    @PostMapping("/{id}/resume/regenerate")
    public ResumeView regenerateResume(@PathVariable Long id) {
        return resumeService.regenerate(currentUser.require().getId(), id);
    }
}