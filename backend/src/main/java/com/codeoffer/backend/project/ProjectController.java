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
import java.net.URI;
import java.net.URISyntaxException;
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
import org.springframework.transaction.annotation.Transactional;

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
        validateRepoUrl(url);
        if (projects.findByRepoUrl(url).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该仓库已录入过,请勿重复提交");
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
                        c.getUserInput(),
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
                        c.getUserInput(),
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
        if (p.getStatus() != ProjectStatus.REVIEW && p.getStatus() != ProjectStatus.DONE) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "当前状态 " + p.getStatus() + " 不可确认,需先生成完毕(REVIEW 状态)");
        }
        p.setStatus(ProjectStatus.DONE);
        p.setUpdatedAt(Instant.now());
        projects.save(p);
        return detail(id);
    }

    /** 删除项目及全部关联数据(卡片/简历/任务),不可恢复。 */
    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        requireProject(id);
        cards.deleteByProjectId(id);
        resumes.findByProjectId(id).ifPresent(resumes::delete);
        tasks.findByProjectIdOrderByIdDesc(id).forEach(tasks::delete);
        projects.deleteById(id);
    }

    /** 重新生成整个项目:重置状态并异步重跑全链路(旧卡片会被替换)。 */
    @PostMapping("/{id}/regenerate")
    public CreateResponse regenerate(@PathVariable Long id) {
        Project p = requireProject(id);
        if (tasks.findFirstByProjectIdOrderByIdDesc(id)
                .map(t -> t.getStatus() == TaskStatus.RUNNING)
                .orElse(false)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有生成任务进行中,请等待完成后再重新生成");
        }
        p.setStatus(ProjectStatus.PENDING);
        p.setUpdatedAt(Instant.now());
        projects.save(p);
        GenTask task = new GenTask();
        task.setProjectId(id);
        task = tasks.save(task);
        generate.runFullGeneration(id, task.getId());
        return new CreateResponse(id, task.getId(), p.getStatus().name());
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
        requireProject(id);
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
        return toCardView(cards.save(card));
    }

    /** 采纳 AI 草稿为定稿(标记为编辑过,防止下次重新生成覆盖)。 */
    @PostMapping("/{id}/cards/{cardId}/adopt")
    public CardView adoptDraft(@PathVariable Long id, @PathVariable Long cardId) {
        requireProject(id);
        KnowledgeCard card = requireCard(cardId);
        if (card.getAiDraft() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂无 AI 草稿");
        }
        card.setContent(card.getAiDraft());
        card.setEdited(true);
        card.setUpdatedAt(Instant.now());
        return toCardView(cards.save(card));
    }

    /** 重新生成单卡:已编辑卡片只更新草稿。 */
    @PostMapping("/{id}/cards/{cardId}/regenerate")
    public CardView regenerateCard(@PathVariable Long id, @PathVariable Long cardId) {
        requireProject(id);
        KnowledgeCard card = requireCard(cardId);
        if (!card.getProjectId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "卡片不属于该项目");
        }
        return toCardView(generate.regenerateCard(cardId));
    }

    /** 用户补充输入(如运行指南的踩坑记录):JSON 存 userInput。 */
    @PostMapping("/{id}/cards/{cardId}/user-input")
    public CardView addUserInput(@PathVariable Long id, @PathVariable Long cardId, @RequestBody UserInputRequest req) {
        requireProject(id);
        KnowledgeCard card = requireCard(cardId);
        String existing = card.getUserInput() == null ? "" : card.getUserInput();
        String merged = (existing + "\n" + (req.input() == null ? "" : req.input())).trim();
        card.setUserInput(merged);
        if (!card.isEdited()) {
            card.setEdited(true); // 用户补充视为编辑,防止覆盖
        }
        card.setUpdatedAt(Instant.now());
        return toCardView(cards.save(card));
    }

    /** 编辑简历 STAR 内容。 */
    @PutMapping("/{id}/resume/star")
    public ResumeView editResume(@PathVariable Long id, @RequestBody EditStarRequest req) {
        requireProject(id);
        ResumeEntry entry = resumes.findByProjectId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "尚无简历产出"));
        if (req.starContent() == null || req.starContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        entry.setStarContent(req.starContent());
        entry.setEdited(true);
        entry.setUpdatedAt(Instant.now());
        return toResumeView(resumes.save(entry));
    }

    /** 重新生成简历与面试题(已编辑字段不被覆盖)。 */
    @PostMapping("/{id}/resume/regenerate")
    public ResumeView regenerateResume(@PathVariable Long id) {
        requireProject(id);
        return toResumeView(generate.regenerateResume(id));
    }

    // ------------------------------------------------------------ M5 自测与掌握度

    public record ReviewRequest(boolean known) {
    }

    private static final int[] REVIEW_INTERVAL_DAYS = {0, 1, 3, 7, 15, 30};

    /** 自评更新掌握度与下次复习时间:掌握 → mastery+1 且间隔递增;生疏 → mastery-2 且立即复习。 */
    @PostMapping("/{id}/cards/{cardId}/review")
    public CardView reviewCard(@PathVariable Long id, @PathVariable Long cardId, @RequestBody ReviewRequest req) {
        requireProject(id);
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
        return toCardView(cards.save(card));
    }

    /** 待复习队列:mastery==0 或到期的卡片,按下次复习时间升序。 */
    @GetMapping("/{id}/review-queue")
    public List<CardView> reviewQueue(@PathVariable Long id) {
        requireProject(id);
        Instant now = Instant.now();
        return cards.findByProjectIdOrderByIdAsc(id).stream()
                .filter(c -> c.getMastery() == 0
                        || (c.getNextReview() != null && !c.getNextReview().isAfter(now)))
                .sorted(Comparator.comparing(
                        c -> c.getNextReview() == null ? Instant.EPOCH : c.getNextReview()))
                .limit(20)
                .map(this::toCardView)
                .toList();
    }

    // ------------------------------------------------------------ helpers

    /** 受信任的仓库托管平台(host 精确匹配或子域)。 */
    private static final List<String> ALLOWED_REPO_HOSTS = List.of(
            "github.com",
            "gitee.com",
            "gitlab.com",
            "gitlab.cn",
            "gitcode.com",
            "bitbucket.org",
            "raw.githubusercontent.com");

    /**
     * 校验仓库地址:仅允许 https(受信任托管平台)或本地 file 仓库(e2e 场景),
     * 拒绝携带凭证(user:pass@)的 URL,防止 SSRF 与凭证泄露。
     */
    private static void validateRepoUrl(String url) {
        // file:// 本地仓库:宽松处理(路径可含空格等字符,不经严格 URI 解析),仅拒绝凭证
        if (url.toLowerCase().startsWith("file://")) {
            if (url.contains("@")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库地址不能携带凭证(user:pass@),请在托管平台单独配置凭证");
            }
            return;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库地址格式不合法");
        }
        if (uri.getUserInfo() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库地址不能携带凭证(user:pass@),请在托管平台单独配置凭证");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库地址缺少协议(如 https://)");
        }
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 https 或本地 file 仓库地址");
        }
        String host = uri.getHost();
        boolean allowed = host != null && ALLOWED_REPO_HOSTS.stream()
                .anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!allowed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "仅支持受信任的仓库托管平台: " + String.join(" / ", ALLOWED_REPO_HOSTS));
        }
    }

    private Project requireProject(Long id) {
        return projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在: " + id));
    }

    private KnowledgeCard requireCard(Long cardId) {
        return cards.findById(cardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "卡片不存在: " + cardId));
    }

    private CardView toCardView(KnowledgeCard c) {
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

    private ResumeView toResumeView(ResumeEntry r) {
        return new ResumeView(r.getStarContent(), r.getInterviewQas(), r.isEdited());
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
