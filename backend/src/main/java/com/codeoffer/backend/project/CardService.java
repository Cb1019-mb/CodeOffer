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

/** 卡片域服务:编辑/采纳/补充输入/重生成/自评与复习队列。归属经项目级校验隔离。 */
@Service
public class CardService {

    private final KnowledgeCardRepository cards;
    private final ProjectService projectService;
    private final GenerateService generate;
    private final com.codeoffer.backend.settings.ProfileService profileService;
    private final com.codeoffer.backend.settings.SettingsService settings;

    public CardService(
            KnowledgeCardRepository cards,
            ProjectService projectService,
            GenerateService generate,
            com.codeoffer.backend.settings.ProfileService profileService,
            com.codeoffer.backend.settings.SettingsService settings) {
        this.cards = cards;
        this.projectService = projectService;
        this.generate = generate;
        this.profileService = profileService;
        this.settings = settings;
    }

    /** 编辑卡片定稿:标记 edited,重新生成不再覆盖。 */
    public CardView editCard(Long userId, Long id, Long cardId, String content) {
        KnowledgeCard card = requireOwnedCard(userId, id, cardId);
        if (content == null || content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        card.setContent(content);
        card.setEdited(true);
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 采纳 AI 草稿为定稿(标记为编辑过,防止下次重新生成覆盖)。 */
    public CardView adoptDraft(Long userId, Long id, Long cardId) {
        KnowledgeCard card = requireOwnedCard(userId, id, cardId);
        if (card.getAiDraft() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "暂无 AI 草稿");
        }
        card.setContent(card.getAiDraft());
        card.setEdited(true);
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 用户补充输入(如运行指南的踩坑记录)。 */
    public CardView addUserInput(Long userId, Long id, Long cardId, String input) {
        KnowledgeCard card = requireOwnedCard(userId, id, cardId);
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
    public CardView regenerateCard(Long userId, Long id, Long cardId) {
        requireOwnedCard(userId, id, cardId);
        profileService.runtimeFor(userId); // 预检:无激活档案 409 友好提示
        return CardView.from(generate.regenerateCard(cardId, userId));
    }

    /** 自评更新掌握度与下次复习时间:间隔/扣减/延迟按用户设置实时生效。 */
    public CardView reviewCard(Long userId, Long id, Long cardId, boolean known) {
        KnowledgeCard card = requireOwnedCard(userId, id, cardId);
        com.codeoffer.backend.settings.SettingsService.ReviewParams rp = settings.reviewFor(userId);
        Instant now = Instant.now();
        if (known) {
            int mastery = Math.min(5, card.getMastery() + 1);
            card.setMastery(mastery);
            card.setNextReview(now.plus(Duration.ofDays(rp.intervalDays().get(mastery))));
        } else {
            card.setMastery(Math.max(0, card.getMastery() - rp.unknownPenalty()));
            card.setNextReview(now.plusSeconds(rp.unknownDelaySeconds()));
        }
        card.setLastReviewed(now);
        card.setUpdatedAt(now);
        return CardView.from(cards.save(card));
    }

    /** 待复习队列:mastery==0 或到期的卡片,按下次复习时间升序,最多 20 条。 */
    public List<CardView> reviewQueue(Long userId, Long id) {
        projectService.requireProject(userId, id);
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

    private KnowledgeCard requireOwnedCard(Long userId, Long id, Long cardId) {
        projectService.requireProject(userId, id);
        KnowledgeCard card = cards.findById(cardId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "卡片不存在: " + cardId));
        if (!card.getProjectId().equals(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "卡片不属于该项目");
        }
        return card;
    }
}