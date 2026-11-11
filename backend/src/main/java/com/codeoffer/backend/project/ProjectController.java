package com.codeoffer.backend.project;

import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.project.ApiViews.CardView;
import com.codeoffer.backend.project.ApiViews.CreateRequest;
import com.codeoffer.backend.project.ApiViews.CreateResponse;
import com.codeoffer.backend.project.ApiViews.ProjectDetail;
import com.codeoffer.backend.project.ApiViews.ProjectView;
import com.codeoffer.backend.project.ApiViews.ResumeView;
import com.codeoffer.backend.project.ApiViews.TaskView;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 项目 API:路由与参数接收。项目域逻辑在 ProjectService;
 * 卡片/简历域逻辑暂留本类,后续任务迁入 CardService/ResumeService。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final KnowledgeCardRepository cards;
    private final ResumeEntryRepository resumes;
    private final GenerateService generate;

    public ProjectController(
            ProjectService projectService,
            KnowledgeCardRepository cards,
            ResumeEntryRepository resumes,
            GenerateService generate) {
        this.projectService = projectService;
        this.cards = cards;
        this.resumes = resumes;
        this.generate = generate;
    }

    @PostMapping
    public CreateResponse create(@RequestBody CreateRequest req) {
        return projectService.create(req);
    }

    @GetMapping
    public List<ProjectView> list() {
        return projectService.list();
    }

    @GetMapping("/{id}")
    public ProjectDetail detail(@PathVariable Long id) {
        return projectService.detail(id);
    }

    @GetMapping("/{id}/task")
    public TaskView latestTask(@PathVariable Long id) {
        return projectService.latestTask(id);
    }

    @GetMapping("/{id}/cards")
    public List<CardView> cards(@PathVariable Long id) {
        return projectService.cards(id);
    }

    @GetMapping("/{id}/resume")
    public ResumeView resume(@PathVariable Long id) {
        return projectService.resume(id);
    }

    @PostMapping("/{id}/confirm")
    public ProjectDetail confirm(@PathVariable Long id) {
        return projectService.confirm(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        projectService.delete(id);
    }

    @PostMapping("/{id}/regenerate")
    public CreateResponse regenerate(@PathVariable Long id) {
        return projectService.regenerate(id);
    }

    // ------------------------------------------------------------ M4 编辑与重新生成

    public record EditContentRequest(String content) {
    }

    public record UserInputRequest(String input) {
    }

    public record EditStarRequest(String starContent) {
    }

    /** 编辑卡片定稿:标记 edited,重新生成不再覆盖。 */
    @PutMapping("/{id}/cards/{cardId}/content")
    public CardView editCard(@PathVariable Long id, @PathVariable Long cardId, @RequestBody EditContentRequest req) {
        projectService.requireProject(id);
        KnowledgeCard card = requireCard(cardId);
        if (!card.getProjectId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "卡片不属于该项目");
        }
        if (req.content() == null || req.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        card.setContent(req.content());
        card.setEdited(true);
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 采纳 AI 草稿为定稿(标记为编辑过,防止下次重新生成覆盖)。 */
    @PostMapping("/{id}/cards/{cardId}/adopt")
    public CardView adoptDraft(@PathVariable Long id, @PathVariable Long cardId) {
        projectService.requireProject(id);
        KnowledgeCard card = requireCard(cardId);
        if (card.getAiDraft() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂无 AI 草稿");
        }
        card.setContent(card.getAiDraft());
        card.setEdited(true);
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 重新生成单卡:已编辑卡片只更新草稿。 */
    @PostMapping("/{id}/cards/{cardId}/regenerate")
    public CardView regenerateCard(@PathVariable Long id, @PathVariable Long cardId) {
        projectService.requireProject(id);
        KnowledgeCard card = requireCard(cardId);
        if (!card.getProjectId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "卡片不属于该项目");
        }
        return CardView.from(generate.regenerateCard(cardId));
    }

    /** 用户补充输入(如运行指南的踩坑记录):JSON 存 userInput。 */
    @PostMapping("/{id}/cards/{cardId}/user-input")
    public CardView addUserInput(@PathVariable Long id, @PathVariable Long cardId, @RequestBody UserInputRequest req) {
        projectService.requireProject(id);
        KnowledgeCard card = requireCard(cardId);
        String existing = card.getUserInput() == null ? "" : card.getUserInput();
        String merged = (existing + "\n" + (req.input() == null ? "" : req.input())).trim();
        card.setUserInput(merged);
        if (!card.isEdited()) {
            card.setEdited(true); // 用户补充视为编辑,防止覆盖
        }
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 编辑简历 STAR 内容。 */
    @PutMapping("/{id}/resume/star")
    public ResumeView editResume(@PathVariable Long id, @RequestBody EditStarRequest req) {
        projectService.requireProject(id);
        ResumeEntry entry = resumes.findByProjectId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "尚无简历产出"));
        if (req.starContent() == null || req.starContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        entry.setStarContent(req.starContent());
        entry.setEdited(true);
        entry.setUpdatedAt(Instant.now());
        return ResumeView.from(resumes.save(entry));
    }

    /** 重新生成简历与面试题(已编辑字段不被覆盖)。 */
    @PostMapping("/{id}/resume/regenerate")
    public ResumeView regenerateResume(@PathVariable Long id) {
        projectService.requireProject(id);
        return ResumeView.from(generate.regenerateResume(id));
    }

    // ------------------------------------------------------------ M5 自测与掌握度

    public record ReviewRequest(boolean known) {
    }

    private static final int[] REVIEW_INTERVAL_DAYS = {0, 1, 3, 7, 15, 30};

    /** 自评更新掌握度与下次复习时间:掌握 → mastery+1 且间隔递增;生疏 → mastery-2 且立即复习。 */
    @PostMapping("/{id}/cards/{cardId}/review")
    public CardView reviewCard(@PathVariable Long id, @PathVariable Long cardId, @RequestBody ReviewRequest req) {
        projectService.requireProject(id);
        KnowledgeCard card = requireCard(cardId);
        if (!card.getProjectId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "卡片不属于该项目");
        }
        Instant now = Instant.now();
        if (req.known()) {
            int mastery = Math.min(5, card.getMastery() + 1);
            card.setMastery(mastery);
            card.setNextReview(now.plus(Duration.ofDays(REVIEW_INTERVAL_DAYS[mastery])));
        } else {
            card.setMastery(Math.max(0, card.getMastery() - 2));
            card.setNextReview(now.plusSeconds(60));
        }
        card.setLastReviewed(now);
        card.setUpdatedAt(now);
        return CardView.from(cards.save(card));
    }

    /** 待复习队列:mastery==0 或到期的卡片,按下次复习时间升序。 */
    @GetMapping("/{id}/review-queue")
    public List<CardView> reviewQueue(@PathVariable Long id) {
        projectService.requireProject(id);
        Instant now = Instant.now();
        return cards.findByProjectIdOrderByIdAsc(id).stream()
                .filter(c -> c.getMastery() == 0
                        || (c.getNextReview() != null && !c.getNextReview().isAfter(now)))
                .sorted(Comparator.comparing(
                        c -> c.getNextReview() == null ? Instant.EPOCH : c.getNextReview()))
                .limit(20)
                .map(CardView::from)
                .toList();
    }

    // ------------------------------------------------------------ helpers

    private KnowledgeCard requireCard(Long cardId) {
        return cards.findById(cardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "卡片不存在: " + cardId));
    }
}