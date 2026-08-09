package com.codeoffer.backend.project;

/**
 * 知识卡片类型(与 ai-service/prompts 的 CARD_TYPES_ZH 对应)。
 */
public enum CardType {
    ARCHITECTURE("架构设计"),
    DIFFICULTY("技术难点"),
    HIGHLIGHT("项目亮点"),
    TAKEAWAY("学习收获"),
    RUN_GUIDE("运行指南"),
    FLOW_DIAGRAM("核心流程图"),
    COMPARE_PRACTICE("对比练习");

    private final String label;

    CardType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
