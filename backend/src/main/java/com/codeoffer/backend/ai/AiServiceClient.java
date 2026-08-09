package com.codeoffer.backend.ai;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Python AI 服务调用客户端(契约见实现计划 §3)。
 * Java 层不直接碰大模型 API,只通过 HTTP 调用本服务。
 *
 * 说明:请求/响应体统一用注入的 ObjectMapper 手动序列化,规避 RestClient
 * 默认 JSON converter 在 Jackson 3 下的解析差异。
 */
@Component
public class AiServiceClient {

    private final RestClient rest;
    private final ObjectMapper mapper;

    public AiServiceClient(
            ObjectMapper mapper,
            @Value("${ai-service.url:http://127.0.0.1:8000}") String baseUrl) {
        this.mapper = mapper;
        // 强制 HTTP/1.1:uvicorn(无 --http2)对 JDK HttpClient 的 HTTP/2 升级协商处理不佳,
        // 会导致请求体被丢弃(422 body missing)。
        HttpClient jdkClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(jdkClient))
                .build();
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        String json = write(body);
        String resp = rest.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .body(String.class);
        return read(resp);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    /** 克隆仓库,返回本地目录路径;缓存命中直接返回。 */
    public String cloneRepo(String repoUrl) {
        Map<String, Object> resp = post("/api/repos/clone", Map.of("repo_url", repoUrl));
        return (String) resp.get("repo_dir");
    }

    /** 静态解析仓库,返回结构化 repo_info。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(String repoDir) {
        return post("/api/repos/analyze", Map.of("repo_dir", repoDir));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> knowledgeCards(Map<String, Object> repoInfo, List<String> types) {
        return post("/api/ai/knowledge-cards", Map.of("repo_info", repoInfo, "types", types));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> flowDiagram(Map<String, Object> repoInfo) {
        return post("/api/ai/flow-diagram", Map.of("repo_info", repoInfo));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resume(Map<String, Object> repoInfo) {
        return post("/api/ai/resume", Map.of("repo_info", repoInfo));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> interviewQuestions(Map<String, Object> repoInfo) {
        return post("/api/ai/interview-questions", Map.of("repo_info", repoInfo));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> healthReport(String repoUrl, Map<String, Object> meta) {
        return post("/api/ai/health-report", Map.of("repo_url", repoUrl, "meta", meta == null ? Map.of() : meta));
    }
}
