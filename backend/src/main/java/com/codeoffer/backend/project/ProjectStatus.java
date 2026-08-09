package com.codeoffer.backend.project;

/**
 * 项目状态机:录入 → 抓取 → 解析 → 生成 → 待确认 → 已入库(或失败)。
 */
public enum ProjectStatus {
    PENDING,
    CLONING,
    ANALYZING,
    GENERATING,
    REVIEW,
    DONE,
    FAILED
}
