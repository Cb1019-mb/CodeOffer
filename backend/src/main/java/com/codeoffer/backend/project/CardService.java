package com.codeoffer.backend.project;

import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.common.ApiException;
import com.codeoffer.backend.project.ApiViews.CardView;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 卡片域服务:编辑/采纳/补充输入/重生成/自评与复习队列。 */
@Service
public class CardService {

    /** 掌握度 0-5 对应的复习间隔(天);A 阶段将改为按用户设置读取。 */
    static final int[] REVIEW_INTERVAL_DAYS = {0, 1, 3, 7, 15, 30};

    private final KnowledgeCardRepository cards;
    private final ProjectService projectService;
    private final GenerateService generate;

    public CardService(
            KnowledgeCardRepository cards,
            ProjectService projectService,
            GenerateService generate) {
        this.cards = cards;
        this.projectService = projectService;
        this.generate = generate;
    }

    /** 编辑卡片定稿:标记 edited,重新生成不再覆盖。 */
    public CardView editCard(Long id, Long cardId, String content) {
        KnowledgeCard card = requireOwnedCard(id, cardId);
        if (content == null || content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        card.setContent(content);
        card.setEdited(true);
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 采纳 AI 草稿为定稿(标记为编辑过,防止下次重新生成覆盖)。 */
    public CardView adoptDraft(Long id, Long cardId) {
        KnowledgeCard card = requireOwnedCard(id, cardId);
        if (card.getAiDraft() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "暂无 AI 草稿");
        }
        card.setContent(card.getAiDraft());
        card.setEdited(true);
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 用户补充输入(如运行指南的踩坑记录)。 */
    public CardView addUserInput(Long id, Long cardId, String input) {
        KnowledgeCard card = requireOwnedCard(id, cardId);
        String existing = card.getUserInput() == null ? "" : card.getUserInput();
        String merged = (existing + "\n" + (input == null ? "" : input)).trim();
        card.setUserInput(merged);
        if (!card.isEdited()) {
            card.setEdited(true); // 用户补充视为编辑,防止覆盖
        }
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 重新生成单卡:归属校验后委托生成服务(已编辑卡片只更新草稿)。 */
    public CardView regenerateCard(Long id, Long cardId) {
        requireOwnedCard(id, cardId);
        return CardView.from(generate.regenerateCard(cardId));
    }

    /** 自评更新掌握度与下次复习时间:掌握 → mastery+1 且间隔递增;生疏 → mastery-2 且立即复习。 */
    public CardView reviewCard(Long id, Long cardId, boolean known) {
        KnowledgeCard card = requireOwnedCard(id, cardId);
        Instant now = Instant.now();
        if (known) {
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

    /** 待复习队列:mastery==0 或到期的卡片,按下次复习时间升序,最多 20 条。 */
    public List<CardView> reviewQueue(Long id) {
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

    private KnowledgeCard requireOwnedCard(Long id, Long cardId) {
        projectService.requireProject(id);
        KnowledgeCard card = cards.findById(cardId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "卡片不存在: " + cardId));
        if (!card.getProjectId().equals(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "卡片不属于该项目");
        }
        return card;
    }
}