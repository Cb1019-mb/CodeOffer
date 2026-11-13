package com.codeoffer.backend.settings;

import com.codeoffer.backend.ai.AiServiceClient;
import com.codeoffer.backend.ai.LlmRuntime;
import com.codeoffer.backend.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** LLM 档案 CRUD/激活/连接测试/import-env;key 加密存储、脱敏返回。 */
@Service
public class ProfileService {

    public record ProfileRequest(String name, String provider, String baseUrl, String apiKey, String model, Boolean verifySsl) {
    }

    public record ProfileView(
            Long id, String name, String provider, String baseUrl, String model,
            String apiKeyMasked, boolean verifySsl, boolean active) {
    }

    private static final Pattern BASE_URL = Pattern.compile("^https?://.+");
    private static final List<String> PROVIDERS = List.of("deepseek", "qwen", "zhipu", "vllm", "custom");

    private final LlmProfileRepository profiles;
    private final SettingsService settings;
    private final CryptoService crypto;
    private final AiServiceClient ai;
    private final String envBaseUrl;
    private final String envApiKey;
    private final String envModel;

    public ProfileService(
            LlmProfileRepository profiles,
            SettingsService settings,
            CryptoService crypto,
            AiServiceClient ai,
            @Value("${LLM_BASE_URL:}") String envBaseUrl,
            @Value("${LLM_API_KEY:}") String envApiKey,
            @Value("${LLM_MODEL:}") String envModel) {
        this.profiles = profiles;
        this.settings = settings;
        this.crypto = crypto;
        this.ai = ai;
        this.envBaseUrl = envBaseUrl;
        this.envApiKey = envApiKey;
        this.envModel = envModel;
    }

    public List<ProfileView> list(Long userId) {
        Long active = settings.activeProfileId(userId);
        return profiles.findByUserIdOrderByIdAsc(userId).stream()
                .map(p -> view(p, active))
                .toList();
    }

    public ProfileView create(Long userId, ProfileRequest req) {
        validate(req);
        LlmProfile p = new LlmProfile();
        p.setUserId(userId);
        apply(p, req, true);
        p = profiles.save(p);
        return view(p, settings.activeProfileId(userId));
    }

    public ProfileView update(Long userId, Long id, ProfileRequest req) {
        validate(req);
        LlmProfile p = requireProfile(userId, id);
        apply(p, req, false);
        p.setUpdatedAt(Instant.now());
        p = profiles.save(p);
        return view(p, settings.activeProfileId(userId));
    }

    public void delete(Long userId, Long id) {
        requireProfile(userId, id);
        if (id.equals(settings.activeProfileId(userId))) {
            throw new ApiException(HttpStatus.CONFLICT, "请先切换激活档案再删除");
        }
        profiles.deleteById(id);
    }

    public void activate(Long userId, Long id) {
        requireProfile(userId, id);
        settings.update(userId, id, null, null, null);
    }

    public Map<String, Object> test(Long userId, Long id) {
        LlmProfile p = requireProfile(userId, id);
        LlmRuntime rt = new LlmRuntime(
                p.getBaseUrl(), crypto.decrypt(p.getApiKeyEnc()), p.getModel(), p.isVerifySsl(),
                "zh", "standard");
        return ai.testConnection(rt);
    }

    /** 服务器主人把 config/.env 的 LLM 配置一键建为自己的档案。 */
    public ProfileView importEnv(Long userId) {
        if (envBaseUrl == null || envBaseUrl.isBlank()
                || envApiKey == null || envApiKey.isBlank()
                || envModel == null || envModel.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "服务器未配置 LLM 环境变量");
        }
        LlmProfile p = new LlmProfile();
        p.setUserId(userId);
        p.setName("从 .env 导入");
        p.setProvider("custom");
        p.setBaseUrl(envBaseUrl.trim());
        p.setApiKeyEnc(crypto.encrypt(envApiKey.trim()));
        p.setModel(envModel.trim());
        p.setVerifySsl(true);
        p = profiles.save(p);
        return view(p, settings.activeProfileId(userId));
    }

    /** 生成运行时快照:无激活档案 409(BYOK,废除 env 回退防 key 借用)。 */
    public LlmRuntime runtimeFor(Long userId) {
        Long active = settings.activeProfileId(userId);
        if (active == null) {
            throw new ApiException(HttpStatus.CONFLICT, "请先在设置中配置 AI 提供商");
        }
        LlmProfile p = profiles.findByIdAndUserId(active, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "请先在设置中配置 AI 提供商"));
        SettingsService.GenerationPrefs prefs = settings.generationFor(userId);
        return new LlmRuntime(
                p.getBaseUrl(), crypto.decrypt(p.getApiKeyEnc()), p.getModel(), p.isVerifySsl(),
                prefs.language(), prefs.verbosity());
    }

    public LlmProfile requireProfile(Long userId, Long id) {
        return profiles.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "档案不存在: " + id));
    }

    private void validate(ProfileRequest req) {
        if (req.name() == null || req.name().isBlank() || req.name().length() > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "档案名需为 1-50 字符");
        }
        if (req.provider() == null || !PROVIDERS.contains(req.provider())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "provider 仅支持 deepseek/qwen/zhipu/vllm/custom");
        }
        if (req.baseUrl() == null || !BASE_URL.matcher(req.baseUrl().trim()).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "端点需为 http(s):// 开头");
        }
        if (req.model() == null || req.model().isBlank() || req.model().length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "模型名需为 1-100 字符");
        }
    }

    private void apply(LlmProfile p, ProfileRequest req, boolean isCreate) {
        p.setName(req.name().trim());
        p.setProvider(req.provider());
        p.setBaseUrl(req.baseUrl().trim());
        p.setModel(req.model().trim());
        p.setVerifySsl(req.verifySsl() == null || req.verifySsl());
        if (isCreate || (req.apiKey() != null && !req.apiKey().isBlank())) {
            if (req.apiKey() == null || req.apiKey().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "新建档案必须提供 API Key");
            }
            p.setApiKeyEnc(crypto.encrypt(req.apiKey()));
        }
    }

    private ProfileView view(LlmProfile p, Long active) {
        return new ProfileView(
                p.getId(), p.getName(), p.getProvider(), p.getBaseUrl(), p.getModel(),
                CryptoService.mask(crypto.decrypt(p.getApiKeyEnc())), p.isVerifySsl(),
                p.getId().equals(active));
    }
}