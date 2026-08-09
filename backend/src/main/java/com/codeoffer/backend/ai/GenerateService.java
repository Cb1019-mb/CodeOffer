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
import java.util.List;
import java.util.Map;
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

    public GenerateService(
            ProjectRepository projects,
            KnowledgeCardRepository cards,
            ResumeEntryRepository resumes,
            GenTaskRepository tasks,
            AiServiceClient ai,
            ObjectMapper mapper) {
        this.projects = projects;
        this.cards = cards;
        this.resumes = resumes;
        this.tasks = tasks;
        this.ai = ai;
        this.mapper = mapper;
    }

    @Async
    public void runFullGeneration(Long projectId, Long taskId) {
        Project project = projects.findById(projectId).orElseThrow();
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
            saveKnowledgeCards(projectId, ai.knowledgeCards(repoInfo, CARD_TYPES));
            task.setProgress(70);
            tasks.save(task);

            saveFlowCard(projectId, ai.flowDiagram(repoInfo));
            task.setProgress(80);
            tasks.save(task);

            Map<String, Object> resumeResp = ai.resume(repoInfo);
            Map<String, Object> qaResp = ai.interviewQuestions(repoInfo);
            saveResumeEntry(projectId, resumeResp, qaResp);
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

    @SuppressWarnings("unchecked")
    private void saveKnowledgeCards(Long projectId, Map<String, Object> resp) {
        cards.deleteByProjectId(projectId);
        List<Map<String, Object>> list = (List<Map<String, Object>>) resp.get("cards");
        if (list == null) {
            return;
        }
        for (Map<String, Object> item : list) {
            KnowledgeCard card = new KnowledgeCard();
            card.setProjectId(projectId);
            card.setType(toCardType((String) item.get("type")));
            card.setTitle((String) item.get("title"));
            String content = (String) item.get("content");
            card.setAiDraft(content);
            card.setContent(content);
            cards.save(card);
        }
    }

    private void saveFlowCard(Long projectId, Map<String, Object> resp) {
        String title = (String) resp.getOrDefault("title", "核心流程图");
        String description = (String) resp.getOrDefault("description", "");
        String mermaid = (String) resp.getOrDefault("mermaid", "");
        String content = "# " + title + "\n\n" + description + "\n\n```mermaid\n" + mermaid + "\n```";
        cards.findByProjectIdAndType(projectId, CardType.FLOW_DIAGRAM).ifPresentOrElse(
                card -> {
                    card.setAiDraft(content);
                    if (!card.isEdited()) {
                        card.setContent(content);
                    }
                    cards.save(card);
                },
                () -> {
                    KnowledgeCard card = new KnowledgeCard();
                    card.setProjectId(projectId);
                    card.setType(CardType.FLOW_DIAGRAM);
                    card.setTitle(title);
                    card.setAiDraft(content);
                    card.setContent(content);
                    cards.save(card);
                });
    }

    private void saveResumeEntry(Long projectId, Map<String, Object> resumeResp, Map<String, Object> qaResp) {
        resumes.findByProjectId(projectId).ifPresentOrElse(
                entry -> {
                    entry.setAiDraft(json(resumeResp) + "\n\n" + json(qaResp));
                    if (!entry.isEdited()) {
                        entry.setStarContent(json(resumeResp.get("star")));
                        entry.setInterviewQas(json(qaResp.get("questions")));
                    }
                    resumes.save(entry);
                },
                () -> {
                    ResumeEntry entry = new ResumeEntry();
                    entry.setProjectId(projectId);
                    entry.setAiDraft(json(resumeResp) + "\n\n" + json(qaResp));
                    entry.setStarContent(json(resumeResp.get("star")));
                    entry.setInterviewQas(json(qaResp.get("questions")));
                    resumes.save(entry);
                });
    }

    private CardType toCardType(String type) {
        try {
            return CardType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            return CardType.TAKEAWAY;
        }
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
}
