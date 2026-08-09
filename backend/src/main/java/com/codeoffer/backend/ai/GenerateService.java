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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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

    /** 全局生成锁:SQLite 单写者,WAL 下并发写会触发 SQLITE_BUSY_SNAPSHOT,同一时间只允许一个生成任务 */
    private final ReentrantLock generationLock = new ReentrantLock();

    public GenerateService(
            ProjectRepository projects,
            KnowledgeCardRepository cards,
            ResumeEntryRepository resumes,
            GenTaskRepository tasks,
            AiServiceClient ai,
            ObjectMapper mapper,
            GenerationWriter writer) {
        this.projects = projects;
        this.cards = cards;
        this.resumes = resumes;
        this.tasks = tasks;
        this.ai = ai;
        this.mapper = mapper;
        this.writer = writer;
    }

    @Async
    public void runFullGeneration(Long projectId, Long taskId) {
        generationLock.lock();
        try {
            doRunFullGeneration(projectId, taskId);
        } finally {
            generationLock.unlock();
        }
    }

    private void doRunFullGeneration(Long projectId, Long taskId) {        Project project = projects.findById(projectId).orElseThrow();
        GenTask task = tasks.findById(taskId).orElseThrow();
        try {
            // 1) 克隆
            transition(project, task, ProjectStatus.CLONING, 5);
            String repoDir = ai.cloneRepo(project.getRepoUrl());
            project.setRepoDir(repoDir);
            projects.save(project);

            // 2) 解析
            transition(project, task, ProjectStatus.ANALYZING, 25);
            Map<String, Object> repoInfo = ai.analyze(repoDir);
            project.setRepoInfo(mapper.writeValueAsString(repoInfo));
            projects.save(project);

            // 3) 生成
            transition(project, task, ProjectStatus.GENERATING, 50);
            Map<String, Object> cardsResp = ai.knowledgeCards(repoInfo, CARD_TYPES);
            retryOnBusy(() -> writer.replaceCards(projectId, castCards(cardsResp.get("cards"))));
            task.setProgress(70);
            tasks.save(task);

            Map<String, Object> flowResp = ai.flowDiagram(repoInfo);
            retryOnBusy(() -> writer.saveFlowCard(
                    projectId,
                    (String) flowResp.getOrDefault("title", "核心流程图"),
                    buildFlowContent(flowResp)));
            task.setProgress(80);
            tasks.save(task);

            Map<String, Object> resumeResp = ai.resume(repoInfo);
            Map<String, Object> qaResp = ai.interviewQuestions(repoInfo);
            retryOnBusy(() -> writer.saveResumeEntry(
                    projectId,
                    json(resumeResp) + "\n\n" + json(qaResp),
                    json(resumeResp.get("star")),
                    json(qaResp.get("questions"))));
            task.setProgress(90);
            tasks.save(task);

            // 4) 待用户确认
            project.setStatus(ProjectStatus.REVIEW);
            projects.save(project);
            task.setStatus(TaskStatus.DONE);
            task.setProgress(100);
            tasks.save(task);
            log.info("项目 {} 全链路生成完成", projectId);
        } catch (Exception e) {
            log.error("项目 {} 生成失败: {}", projectId, e.getMessage(), e);
            project.setStatus(ProjectStatus.FAILED);
            projects.save(project);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMsg(truncate(e.getMessage(), 1900));
            tasks.save(task);
        }
    }

    private void transition(Project project, GenTask task, ProjectStatus status, int progress) {
        project.setStatus(status);
        projects.save(project);
        task.setProgress(progress);
        tasks.save(task);
    }

    /**
     * 写操作遇 SQLite 锁冲突(SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT)时重试。
     * busy_timeout 对 SNAPSHOT 错误无效(等待无法解决快照过期),需事务级重试。
     */
    private void retryOnBusy(Runnable op) {
        int attempts = 0;
        while (true) {
            try {
                op.run();
                return;
            } catch (DataAccessException e) {
                if (isBusyError(e) && ++attempts < 3) {
                    try {
                        Thread.sleep(300L * attempts);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
    }

    private static boolean isBusyError(DataAccessException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("SQLITE_BUSY") || msg.contains("database is locked"));
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
    public KnowledgeCard regenerateCard(Long cardId) {
        KnowledgeCard card = cards.findById(cardId).orElseThrow();
        Project project = projects.findById(card.getProjectId()).orElseThrow();
        if (project.getRepoInfo() == null) {
            throw new IllegalStateException("项目尚未完成解析,无法重新生成");
        }
        Map<String, Object> repoInfo = readRepoInfo(project);
        String newDraft;
        if (card.getType() == CardType.FLOW_DIAGRAM) {
            newDraft = buildFlowContent(ai.flowDiagram(repoInfo));
        } else {
            Map<String, Object> resp = ai.knowledgeCards(repoInfo, List.of(card.getType().name().toLowerCase()));
            List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("cards");
            if (items == null || items.isEmpty()) {
                throw new IllegalStateException("AI 未返回卡片内容");
            }
            Map<String, Object> item = items.get(0);
            card.setTitle((String) item.getOrDefault("title", card.getTitle()));
            newDraft = (String) item.get("content");
        }
        card.setAiDraft(newDraft);
        if (!card.isEdited()) {
            card.setContent(newDraft);
        }
        card.setUpdatedAt(Instant.now());
        return cards.save(card);
    }

    /** 重新生成简历与面试题;已编辑的字段不被覆盖。 */
    @SuppressWarnings("unchecked")
    public ResumeEntry regenerateResume(Long projectId) {
        Project project = projects.findById(projectId).orElseThrow();
        if (project.getRepoInfo() == null) {
            throw new IllegalStateException("项目尚未完成解析,无法重新生成");
        }
        Map<String, Object> repoInfo = readRepoInfo(project);
        Map<String, Object> resumeResp = ai.resume(repoInfo);
        Map<String, Object> qaResp = ai.interviewQuestions(repoInfo);

        ResumeEntry entry = resumes.findByProjectId(projectId).orElseGet(() -> {
            ResumeEntry e = new ResumeEntry();
            e.setProjectId(projectId);
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
}
