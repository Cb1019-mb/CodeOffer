package com.codeoffer.backend.ai;

import com.codeoffer.backend.project.CardType;
import com.codeoffer.backend.project.KnowledgeCard;
import com.codeoffer.backend.project.KnowledgeCardRepository;
import com.codeoffer.backend.project.ResumeEntry;
import com.codeoffer.backend.project.ResumeEntryRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 生成结果的持久化写入器:删除+重建、更新等写操作放在独立短事务中执行。
 * 之所以独立成 bean,是因为 GenerateService 的 @Async 编排方法里直接调用
 * 自身带 @Transactional 的方法不会经过 Spring 代理(自调用),事务不生效。
 */
@Service
public class GenerationWriter {

    private final KnowledgeCardRepository cards;
    private final ResumeEntryRepository resumes;
    private final ObjectMapper mapper;

    public GenerationWriter(
            KnowledgeCardRepository cards,
            ResumeEntryRepository resumes,
            ObjectMapper mapper) {
        this.cards = cards;
        this.resumes = resumes;
        this.mapper = mapper;
    }

    /** 全量替换知识卡片:删除旧卡后按 AI 返回重建。 */
    @Transactional
    public void replaceCards(Long projectId, List<Map<String, Object>> items) {
        cards.deleteByProjectId(projectId);
        if (items == null) {
            return;
        }
        for (Map<String, Object> item : items) {
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

    /** 保存或更新流程图卡片(编辑过的保留定稿)。 */
    @Transactional
    public void saveFlowCard(Long projectId, String title, String content) {
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

    /** 保存或更新简历条目(编辑过的字段保留定稿)。 */
    @Transactional
    public void saveResumeEntry(
            Long projectId, String aiDraft, String starJson, String qasJson) {
        resumes.findByProjectId(projectId).ifPresentOrElse(
                entry -> {
                    entry.setAiDraft(aiDraft);
                    if (!entry.isEdited()) {
                        entry.setStarContent(starJson);
                        entry.setInterviewQas(qasJson);
                    }
                    resumes.save(entry);
                },
                () -> {
                    ResumeEntry entry = new ResumeEntry();
                    entry.setProjectId(projectId);
                    entry.setAiDraft(aiDraft);
                    entry.setStarContent(starJson);
                    entry.setInterviewQas(qasJson);
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
}
