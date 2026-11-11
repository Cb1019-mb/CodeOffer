package com.codeoffer.backend.ai;

import com.codeoffer.backend.project.CardType;
import com.codeoffer.backend.project.GenTask;
import com.codeoffer.backend.project.GenTask.TaskStatus;
import com.codeoffer.backend.project.GenTaskRepository;
import com.codeoffer.backend.project.KnowledgeCard;
import com.codeoffer.backend.project.KnowledgeCardRepository;
import com.codeoffer.backend.project.Project;
import com.codeoffer.backend.project.ProjectRepository;
import com.codeoffer.backend.project.ProjectStatus;
import com.codeoffer.backend.project.ResumeEntry;
import com.codeoffer.backend.project.ResumeEntryRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 全链路生成编排:克隆 → 解析 → 知识卡片/流程图/简历/面试题。
 * 异步执行,通过 GenTask 实时上报进度;任何一步失败则项目与任务置 FAILED。
 */
@Service
public class GenerateService {

    private static final Logger log = LoggerFactory.getLogger(GenerateService.class);

    /** knowledge-cards 接口请求的卡片类型(基础 4 类 + 运行指南;流程图单独接口) */
    private static final List<String> CARD_TYPES =
            List.of("architecture", "difficulty", "highlight", "takeaway", "run_guide");

    private final ProjectRepository projects;
    private final KnowledgeCardRepository cards;
    private final ResumeEntryRepository resumes;
    private final GenTaskRepository tasks;
    private final AiServiceClient ai;
    private final ObjectMapper mapper;
    private final GenerationWriter writer;
    private final com.codeoffer.backend.settings.ProfileService profileService;

    /** 全局生成锁:同一时间只允许一个生成任务,限制并发 LLM 调用并串行写入。 */
    private final ReentrantLock generationLock = new ReentrantLock();

    public GenerateService(
            ProjectRepository projects,
            KnowledgeCardRepository cards,
            ResumeEntryRepository resumes,
            GenTaskRepository tasks,
            AiServiceClient ai,
            ObjectMapper mapper,
            GenerationWriter writer,
            com.codeoffer.backend.settings.ProfileService profileService) {
        this.projects = projects;
        this.cards = cards;
        this.resumes = resumes;
        this.tasks = tasks;
        this.ai = ai;
        this.mapper = mapper;
        this.writer = writer;
        this.profileService = profileService;
    }

    @Async
    public void runFullGeneration(Long projectId, Long taskId, Long userId) {
        generationLock.lock();
        try {
            doRunFullGeneration(projectId, taskId, userId);
        } finally {
            generationLock.unlock();
        }
    }

    /** 应用启动时清理孤儿任务:上次进程中断残留的 RUNNING 任务与进行中状态一并置 FAILED。
     *  仅清理 60 秒前仍在 RUNNING 的任务,避免误标另一实例正在执行的生成。 */
    @PostConstruct
    public void sweepOrphanedTasks() {
        Instant cutoff = Instant.now().minusSeconds(60);
        List<GenTask> orphans = tasks.findByStatus(TaskStatus.RUNNING).stream()
                .filter(t -> t.getUpdatedAt() == null || t.getUpdatedAt().isBefore(cutoff))
                .toList();
        for (GenTask t : orphans) {
            t.setStatus(TaskStatus.FAILED);
            t.setErrorMsg("服务重启中断,生成任务未完成,请重新生成");
            tasks.save(t);
            projects.findById(t.getProjectId()).ifPresent(p -> {
                if (p.getStatus() != ProjectStatus.DONE && p.getStatus() != ProjectStatus.REVIEW) {
                    p.setStatus(ProjectStatus.FAILED);
                    projects.save(p);
                }
            });
        }
        if (!orphans.isEmpty()) {
            log.info("启动清理:将 {} 个中断的生成任务标记为 FAILED", orphans.size());
        }
    }

    private void doRunFullGeneration(Long projectId, Long taskId, Long userId) {
        Project project;
        GenTask task;
        try {
            project = projects.findById(projectId).orElseThrow();
            task = tasks.findById(taskId).orElseThrow();
        } catch (NoSuchElementException e) {
            log.warn("生成任务 {}/{} 已被删除,跳过执行", projectId, taskId);
            return;
        }
        try {
            // 0) 用户 LLM 配置快照:生成中途切换档案不影响本次任务
            LlmRuntime rt = profileService.runtimeFor(userId);

            // 1) 克隆
            transition(project, task, ProjectStatus.CLONING, 5);
            String repoDir = ai.cloneRepo(project.getRepoUrl());
            project.setRepoDir(repoDir);
            saveProject(project);

            // 2) 解析
            transition(project, task, ProjectStatus.ANALYZING, 20);
            Map<String, Object> repoInfo = ai.analyze(repoDir);
            project.setRepoInfo(mapper.writeValueAsString(repoInfo));
            // 描述与技术栈(解析自 README 与依赖清单,按主次排列)写入项目信息
            String desc = String.valueOf(repoInfo.getOrDefault("description", ""));
            project.setDescription(desc.isBlank() || "null".equals(desc) ? null : desc);
            Object rawStack = repoInfo.get("tech_stack");
            if (rawStack instanceof List<?> list) {
                List<String> stack = list.stream()
                        .map(String::valueOf)
                        .filter(s -> !s.isBlank() && !"null".equals(s))
                        .toList();
                project.setTechStack(stack.isEmpty() ? null : String.join(", ", stack));
            }
            saveProject(project);

            // 2.5) 体检报告(增值产物,失败不阻断主流程)
            try {
                Map<String, Object> healthResp = ai.healthReport(project.getRepoUrl(), repoInfo, rt);
                project.setHealthReport(buildHealthContent(healthResp));
                saveProject(project);
            } catch (Exception e) {
                log.warn("项目 {} 体检报告生成失败(不阻断主流程): {}", projectId, e.getMessage());
            }
            task.setProgress(35);
            saveTask(task);

            // 3) 生成
            transition(project, task, ProjectStatus.GENERATING, 50);
            Map<String, Object> cardsResp = ai.knowledgeCards(repoInfo, CARD_TYPES, rt);
            writer.replaceCards(projectId, project.getUserId(), castCards(cardsResp.get("cards")));
            task.setProgress(70);
            saveTask(task);

            Map<String, Object> flowResp = ai.flowDiagram(repoInfo, rt);
            writer.saveFlowCard(
                    projectId,
                    project.getUserId(),
                    (String) flowResp.getOrDefault("title", "核心流程图"),
                    buildFlowContent(flowResp));
            task.setProgress(80);
            saveTask(task);

            Map<String, Object> resumeResp = ai.resume(repoInfo, rt);
            Map<String, Object> qaResp = ai.interviewQuestions(repoInfo, rt);
            writer.saveResumeEntry(
                    projectId,
                    project.getUserId(),
                    json(resumeResp) + "\n\n" + json(qaResp),
                    json(resumeResp.get("star")),
                    json(qaResp.get("questions")));
            task.setProgress(90);
            saveTask(task);

            // 4) 待用户确认
            project.setStatus(ProjectStatus.REVIEW);
            project.setUpdatedAt(Instant.now());
            saveProject(project);
            task.setStatus(TaskStatus.DONE);
            task.setProgress(100);
            saveTask(task);
            log.info("项目 {} 全链路生成完成", projectId);
        } catch (Exception e) {
            if (!projects.existsById(projectId)) {
                log.info("项目 {} 已被删除,丢弃生成结果", projectId);
                return;
            }
            log.error("项目 {} 生成失败: {}", projectId, e.getMessage(), e);
            project.setStatus(ProjectStatus.FAILED);
            saveProject(project);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMsg(truncate(e.getMessage(), 1900));
            saveTask(task);
        }
    }

    /** 项目已被删除时跳过写库(避免 merge 复活已删数据)。 */
    private void saveProject(Project project) {
        if (projects.existsById(project.getId())) {
            projects.save(project);
        }
    }

    /** 任务已被删除时跳过写库。 */
    private void saveTask(GenTask task) {
        if (tasks.existsById(task.getId())) {
            task.setUpdatedAt(Instant.now()); // 刷新活跃时间:供启动清理判断任务是否仍在运行
            tasks.save(task);
        }
    }

    private void transition(Project project, GenTask task, ProjectStatus status, int progress) {
        project.setStatus(status);
        project.setUpdatedAt(Instant.now());
        saveProject(project);
        task.setProgress(progress);
        saveTask(task);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castCards(Object raw) {
        return raw == null ? List.of() : (List<Map<String, Object>>) raw;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------ 局部重新生成(M4)

    /** 重新生成单张卡片;已编辑过的卡片只更新 AI 草稿,不覆盖用户定稿。 */
    @SuppressWarnings("unchecked")
    public KnowledgeCard regenerateCard(Long cardId, Long userId) {
        KnowledgeCard card = cards.findById(cardId).orElseThrow();
        Project project = projects.findById(card.getProjectId()).orElseThrow();
        if (project.getRepoInfo() == null) {
            throw new IllegalStateException("项目尚未完成解析,无法重新生成");
        }
        LlmRuntime rt = profileService.runtimeFor(userId);
        Map<String, Object> repoInfo = readRepoInfo(project);
        String newDraft;
        if (card.getType() == CardType.FLOW_DIAGRAM) {
            newDraft = buildFlowContent(ai.flowDiagram(repoInfo, rt));
        } else {
            Map<String, Object> resp = ai.knowledgeCards(repoInfo, List.of(card.getType().name().toLowerCase()), rt);
            List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("cards");
            if (items == null || items.isEmpty()) {
                throw new IllegalStateException("AI 未返回卡片内容");
            }
            Map<String, Object> item = items.get(0);
            card.setTitle((String) item.getOrDefault("title", card.getTitle()));
            Object rawContent = item.get("content");
            newDraft = rawContent == null ? null : String.valueOf(rawContent);
        }
        if (newDraft != null) {
            card.setAiDraft(newDraft);
            if (!card.isEdited()) {
                card.setContent(newDraft);
            }
        }
        card.setUpdatedAt(Instant.now());
        return cards.save(card);
    }

    /** 重新生成简历与面试题;已编辑的字段不被覆盖。 */
    @SuppressWarnings("unchecked")
    public ResumeEntry regenerateResume(Long projectId, Long userId) {
        Project project = projects.findById(projectId).orElseThrow();
        if (project.getRepoInfo() == null) {
            throw new IllegalStateException("项目尚未完成解析,无法重新生成");
        }
        LlmRuntime rt = profileService.runtimeFor(userId);
        Map<String, Object> repoInfo = readRepoInfo(project);
        Map<String, Object> resumeResp = ai.resume(repoInfo, rt);
        Map<String, Object> qaResp = ai.interviewQuestions(repoInfo, rt);

        ResumeEntry entry = resumes.findByProjectId(projectId).orElseGet(() -> {
            ResumeEntry e = new ResumeEntry();
            e.setProjectId(projectId);
            e.setUserId(project.getUserId());
            return e;
        });
        entry.setAiDraft(json(resumeResp) + "\n\n" + json(qaResp));
        if (!entry.isEdited()) {
            entry.setStarContent(json(resumeResp.get("star")));
            entry.setInterviewQas(json(qaResp.get("questions")));
        }
        entry.setUpdatedAt(Instant.now());
        return resumes.save(entry);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRepoInfo(Project project) {
        try {
            return mapper.readValue(project.getRepoInfo(), mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            throw new IllegalStateException("仓库解析信息损坏: " + e.getMessage(), e);
        }
    }

    private static String buildFlowContent(Map<String, Object> resp) {
        String title = (String) resp.getOrDefault("title", "核心流程图");
        String description = (String) resp.getOrDefault("description", "");
        String mermaid = (String) resp.getOrDefault("mermaid", "");
        return "# " + title + "\n\n" + description + "\n\n```mermaid\n" + mermaid + "\n```";
    }

    /** 体检报告 JSON → Markdown;resp 为 null 时返回 null,字段缺失按空串降级。 */
    static String buildHealthContent(Map<String, Object> resp) {
        if (resp == null) {
            return null;
        }
        String verdict = str(resp.get("verdict"));
        String score = str(resp.get("score"));
        StringBuilder sb = new StringBuilder("# 项目体检报告\n\n");
        sb.append("**结论**：").append(verdict.isEmpty() ? "未知" : verdict);
        if (!score.isEmpty()) {
            sb.append("（评分 ").append(score).append("/10）");
        }
        sb.append("\n\n## 评估理由\n");
        appendItems(sb, resp.get("reasons"), "- ");
        sb.append("\n## 学习路线\n");
        appendItems(sb, resp.get("learning_path"), "1. ");
        sb.append("\n## 潜在风险\n").append(str(resp.get("risk")));
        return sb.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void appendItems(StringBuilder sb, Object raw, String prefix) {
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                sb.append(prefix).append(str(item)).append('\n');
            }
        }
    }
}
