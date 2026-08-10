package com.codeoffer.backend.ai;

import com.codeoffer.backend.project.CardType;
import com.codeoffer.backend.project.KnowledgeCard;
import com.codeoffer.backend.project.KnowledgeCardRepository;
import com.codeoffer.backend.project.ResumeEntry;
import com.codeoffer.backend.project.ResumeEntryRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(GenerationWriter.class);

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

    /**
     * 全量替换知识卡片:删除未编辑的旧卡后按 AI 返回重建;
     * 已编辑(edited)的卡片保留定稿,仅更新 AI 草稿,兑现「已编辑不覆盖」承诺。
     */
    @Transactional
    public void replaceCards(Long projectId, List<Map<String, Object>> items) {
        List<KnowledgeCard> existing = cards.findByProjectIdOrderByIdAsc(projectId);
        existing.stream().filter(c -> !c.isEdited()).forEach(cards::delete);
        if (items == null) {
            return;
        }

        // 按类型分组 AI 返回(大小写不敏感)
        Map<String, Map<String, Object>> byType = items.stream()
                .filter(i -> i.get("type") != null)
                .collect(Collectors.toMap(
                        i -> String.valueOf(i.get("type")).toLowerCase(),
                        i -> i,
                        (a, b) -> b));

        // 已编辑卡片:仅刷新 AI 草稿,不覆盖定稿
        for (KnowledgeCard card : existing) {
            if (!card.isEdited()) {
                continue;
            }
            Map<String, Object> item = byType.get(card.getType().name().toLowerCase());
            if (item == null) {
                continue;
            }
            String draft = item.get("content") == null ? null : String.valueOf(item.get("content"));
            if (draft != null) {
                card.setAiDraft(draft);
                cards.save(card);
            }
        }

        // 重建:跳过已被已编辑卡片占用的类型
        Set<String> editedTypes = existing.stream()
                .filter(KnowledgeCard::isEdited)
                .map(c -> c.getType().name().toLowerCase())
                .collect(Collectors.toSet());
        for (Map<String, Object> item : items) {
            String typeName = item.get("type") == null ? null : String.valueOf(item.get("type"));
            if (typeName == null || editedTypes.contains(typeName.toLowerCase())) {
                continue;
            }
            KnowledgeCard card = new KnowledgeCard();
            card.setProjectId(projectId);
            card.setType(toCardType(typeName));
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
            log.warn("未知卡片类型: {},降级为 TAKEAWAY", type);
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
