package com.codeoffer.backend.ai;

/** 一次生成任务的 LLM 配置快照(请求线程内取得,异步全程透传)。 */
public record LlmRuntime(
        String baseUrl, String apiKey, String model, boolean verifySsl,
        String language, String verbosity) {
}