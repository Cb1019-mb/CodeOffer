package com.codeoffer.backend.project;

import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.common.ApiException;
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
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 项目域服务:录入/列表/详情/确认/删除/整仓重生成与仓库 URL 安全校验。 */
@Service
public class ProjectService {

    /** 受信任的仓库托管平台(host 精确匹配或子域)。 */
    private static final List<String> ALLOWED_REPO_HOSTS = List.of(
            "github.com", "gitee.com", "gitlab.com", "gitlab.cn",
            "gitcode.com", "bitbucket.org", "raw.githubusercontent.com");

    private final ProjectRepository projects;
    private final KnowledgeCardRepository cards;
    private final ResumeEntryRepository resumes;
    private final GenTaskRepository tasks;
    private final GenerateService generate;

    public ProjectService(
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

    // 注意:不加 @Transactional——@Async 生成线程在 READ_COMMITTED 下看不到未提交行,
    // 包事务会导致生成任务被当作「已删除」跳过。两次 save 各自原子,与现状一致。
    public CreateResponse create(CreateRequest req) {
        String url = req.repoUrl() == null ? "" : req.repoUrl().trim();
        if (url.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "repoUrl 不能为空");
        }
        validateRepoUrl(url);
        if (projects.findByRepoUrl(url).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "该仓库已录入过,请勿重复提交");
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

    public List<ProjectView> list() {
        return projects.findAll().stream()
                .map(this::toView)
                .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
                .toList();
    }

    public ProjectDetail detail(Long id) {
        Project p = requireProject(id);
        List<CardView> cardViews = cards.findByProjectIdOrderByIdAsc(id).stream()
                .map(CardView::from)
                .toList();
        ResumeView resume = resumes.findByProjectId(id).map(ResumeView::from).orElse(null);
        return new ProjectDetail(
                p.getId(), p.getName(), p.getRepoUrl(), p.getDescription(),
                p.getStatus().name(), p.getTechStack(), p.getHealthReport(),
                cardViews, resume);
    }

    public TaskView latestTask(Long id) {
        requireProject(id);
        return tasks.findFirstByProjectIdOrderByIdDesc(id)
                .map(t -> new TaskView(
                        t.getId(), t.getType().name(), t.getStatus().name(),
                        t.getProgress(), t.getErrorMsg()))
                .orElse(null);
    }

    public List<CardView> cards(Long id) {
        requireProject(id);
        return cards.findByProjectIdOrderByIdAsc(id).stream()
                .map(CardView::from)
                .toList();
    }

    public ResumeView resume(Long id) {
        requireProject(id);
        return resumes.findByProjectId(id)
                .map(ResumeView::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "尚无简历产出"));
    }

    public ProjectDetail confirm(Long id) {
        Project p = requireProject(id);
        if (p.getStatus() != ProjectStatus.REVIEW && p.getStatus() != ProjectStatus.DONE) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "当前状态 " + p.getStatus() + " 不可确认,需先生成完毕(REVIEW 状态)");
        }
        p.setStatus(ProjectStatus.DONE);
        p.setUpdatedAt(Instant.now());
        projects.save(p);
        return detail(id);
    }

    @Transactional
    public void delete(Long id) {
        requireProject(id);
        cards.deleteByProjectId(id);
        resumes.findByProjectId(id).ifPresent(resumes::delete);
        tasks.findByProjectIdOrderByIdDesc(id).forEach(tasks::delete);
        projects.deleteById(id);
    }

    public CreateResponse regenerate(Long id) {
        Project p = requireProject(id);
        if (tasks.findFirstByProjectIdOrderByIdDesc(id)
                .map(t -> t.getStatus() == TaskStatus.RUNNING)
                .orElse(false)) {
            throw new ApiException(HttpStatus.CONFLICT, "已有生成任务进行中,请等待完成后再重新生成");
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

    public Project requireProject(Long id) {
        return projects.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "项目不存在: " + id));
    }

    /**
     * 校验仓库地址:仅允许 https(受信任托管平台)或本地 file 仓库(e2e 场景),
     * 拒绝携带凭证(user:pass@)的 URL,防止 SSRF 与凭证泄露。
     */
    private static void validateRepoUrl(String url) {
        if (url.toLowerCase().startsWith("file://")) {
            if (url.contains("@")) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "仓库地址不能携带凭证(user:pass@),请在托管平台单独配置凭证");
            }
            return;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仓库地址格式不合法");
        }
        if (uri.getUserInfo() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "仓库地址不能携带凭证(user:pass@),请在托管平台单独配置凭证");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仓库地址缺少协议(如 https://)");
        }
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仅支持 https 或本地 file 仓库地址");
        }
        String host = uri.getHost();
        boolean allowed = host != null && ALLOWED_REPO_HOSTS.stream()
                .anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!allowed) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "仅支持受信任的仓库托管平台: " + String.join(" / ", ALLOWED_REPO_HOSTS));
        }
    }

    private ProjectView toView(Project p) {
        List<KnowledgeCard> list = cards.findByProjectIdOrderByIdAsc(p.getId());
        long mastered = list.stream().filter(c -> c.getMastery() >= 3).count();
        long due = list.stream()
                .filter(c -> c.getMastery() > 0 && c.getNextReview() != null
                        && c.getNextReview().isBefore(Instant.now()))
                .count();
        return new ProjectView(
                p.getId(), p.getName(), p.getRepoUrl(), p.getStatus().name(),
                (long) list.size(), mastered, due, p.getUpdatedAt());
    }

    private static String deriveName(String repoUrl) {
        String trimmed = repoUrl.endsWith(".git") ? repoUrl.substring(0, repoUrl.length() - 4) : repoUrl;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }
}