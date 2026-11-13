package com.codeoffer.backend.settings;

import com.codeoffer.backend.common.ApiException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 用户设置 KV:复习参数/生成偏好/激活档案;含校验与默认值。 */
@Service
public class SettingsService {

    public record ReviewParams(List<Integer> intervalDays, int unknownPenalty, int unknownDelaySeconds) {
    }

    public record GenerationPrefs(String language, String verbosity) {
    }

    /** 网络偏好:仓库克隆代理(http/https/socks5),空串表示直连。 */
    public record NetworkPrefs(String proxyUrl) {
    }

    public record SettingsView(Long activeProfileId, ReviewParams review, GenerationPrefs generation, NetworkPrefs network) {
    }

    public static final String KEY_ACTIVE = "active_profile_id";
    public static final String KEY_REVIEW = "review_params";
    public static final String KEY_GENERATION = "generation_prefs";
    public static final String KEY_NETWORK = "network_prefs";

    private static final ReviewParams DEFAULT_REVIEW =
            new ReviewParams(List.of(0, 1, 3, 7, 15, 30), 2, 60);
    private static final GenerationPrefs DEFAULT_GENERATION = new GenerationPrefs("zh", "standard");
    private static final NetworkPrefs DEFAULT_NETWORK = new NetworkPrefs("");

    private final AppSettingRepository settings;
    private final ObjectMapper mapper;

    public SettingsService(AppSettingRepository settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public SettingsView get(Long userId) {
        return new SettingsView(activeProfileId(userId), reviewFor(userId), generationFor(userId), networkFor(userId));
    }

    public Long activeProfileId(Long userId) {
        return read(userId, KEY_ACTIVE, Long.class, null);
    }

    public ReviewParams reviewFor(Long userId) {
        return read(userId, KEY_REVIEW, ReviewParams.class, DEFAULT_REVIEW);
    }

    public GenerationPrefs generationFor(Long userId) {
        return read(userId, KEY_GENERATION, GenerationPrefs.class, DEFAULT_GENERATION);
    }

    public NetworkPrefs networkFor(Long userId) {
        return read(userId, KEY_NETWORK, NetworkPrefs.class, DEFAULT_NETWORK);
    }

    /** 局部更新:null 字段不改。 */
    public void update(Long userId, Long activeProfileId, ReviewParams review, GenerationPrefs generation, NetworkPrefs network) {
        if (activeProfileId != null) {
            write(userId, KEY_ACTIVE, activeProfileId);
        }
        if (review != null) {
            validateReview(review);
            write(userId, KEY_REVIEW, review);
        }
        if (generation != null) {
            validateGeneration(generation);
            write(userId, KEY_GENERATION, generation);
        }
        if (network != null) {
            validateNetwork(network);
            write(userId, KEY_NETWORK, new NetworkPrefs(network.proxyUrl() == null ? "" : network.proxyUrl().trim()));
        }
    }

    private void validateReview(ReviewParams r) {
        List<Integer> days = r.intervalDays();
        if (days == null || days.size() != 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "复习间隔需为 6 个等级");
        }
        int prev = -1;
        for (Integer d : days) {
            if (d == null || d < 0 || d > 365 || d < prev) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "复习间隔需为 0-365 且非递减");
            }
            prev = d;
        }
        if (r.unknownPenalty() < 0 || r.unknownPenalty() > 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "生疏扣减需为 0-5");
        }
        if (r.unknownDelaySeconds() < 0 || r.unknownDelaySeconds() > 86400) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "生疏延迟需为 0-86400 秒");
        }
    }

    private void validateGeneration(GenerationPrefs g) {
        if (!List.of("zh", "en").contains(g.language())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "语言仅支持 zh / en");
        }
        if (!List.of("concise", "standard", "detailed").contains(g.verbosity())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "篇幅仅支持 concise / standard / detailed");
        }
    }

    private void validateNetwork(NetworkPrefs n) {
        String url = n.proxyUrl() == null ? "" : n.proxyUrl().trim();
        if (url.isEmpty()) {
            return;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "代理地址格式不合法");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!List.of("http", "https", "socks5", "socks5h").contains(scheme)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "代理仅支持 http / https / socks5 协议");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "代理地址需包含主机名,如 http://127.0.0.1:7890");
        }
        if (uri.getUserInfo() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "代理地址不能携带凭证(user:pass@)");
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "代理端口需为 1-65535");
        }
        String path = uri.getPath();
        if ((path != null && !path.isEmpty() && !path.equals("/"))
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "代理地址不应包含路径或参数");
        }
    }

    private <T> T read(Long userId, String key, Class<T> type, T fallback) {
        return settings.findByUserIdAndSettingKey(userId, key)
                .map(s -> mapper.readValue(s.getValue(), type))
                .orElse(fallback);
    }

    private void write(Long userId, String key, Object value) {
        AppSetting s = settings.findByUserIdAndSettingKey(userId, key).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setUserId(userId);
            n.setSettingKey(key);
            return n;
        });
        s.setValue(mapper.writeValueAsString(value));
        s.setUpdatedAt(Instant.now());
        settings.save(s);
    }
}