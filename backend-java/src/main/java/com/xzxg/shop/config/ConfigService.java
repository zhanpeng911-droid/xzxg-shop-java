package com.xzxg.shop.config;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.domain.AppConfig;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Config center with optional Nacos persistence. Nacos values are loaded at startup
 * and refreshed by {@link NacosRefreshJob}; selected keys drive live components.
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final Map<String, AppConfig> configs = new ConcurrentHashMap<>();
    private final ShopProperties properties;
    private final ObjectProvider<NacosConfigClient> nacos;
    private final ObjectProvider<com.xzxg.shop.common.web.RateLimitFilter> rateLimitFilter;

    public ConfigService(ShopProperties properties, ObjectProvider<NacosConfigClient> nacos,
                         ObjectProvider<com.xzxg.shop.common.web.RateLimitFilter> rateLimitFilter) {
        this.properties = properties;
        this.nacos = nacos;
        this.rateLimitFilter = rateLimitFilter;
        put("ai.active_provider", "qwen", "string", "当前生效模型供应商：qwen 或 doubao", "app", false);
        put("ai.qwen.base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "string", "千问 OpenAI 兼容模型服务 Base URL", "app", false);
        put("ai.enabled", "true", "bool", "是否启用真实模型调用", "app", false);
        put("ai.enable_thinking", "false", "bool", "是否启用模型思考模式", "app", false);
        put("http.cors.allowed_origins", "*", "string", "允许跨域访问的 Origin", "infra", false);
        put("http.trusted_proxy_cidrs", "", "string", "可信反向代理 CIDR", "infra", false);
        put("http.trust_all_proxies", "false", "bool", "是否信任所有代理头", "infra", false);
        put("files.max_upload_bytes", "10485760", "int", "文件上传最大字节数", "infra", false);
        put("tts.provider", "xunfei", "string", "TTS 供应商：xunfei 或 doubao", "infra", false);
        put("xunfei.tts.enabled", "false", "bool", "是否启用讯飞在线语音合成代理", "infra", false);
        put("xunfei.tts.max_runes", "800", "int", "TTS 单次合成最大字符数", "infra", false);
        put("risk.enabled", "true", "bool", "是否启用风控检查", "infra", false);
        put("retrieval.keyword.top_n", "200", "int", "关键词召回数量", "infra", false);
        put("retrieval.vector.top_n", "40", "int", "向量召回数量", "infra", false);
    }

    @PostConstruct
    void initialize() {
        NacosConfigClient client = nacos.getIfAvailable();
        if (client == null) {
            return;
        }
        try {
            var remote = client.load();
            if (!remote.isEmpty()) {
                mergeRemote(remote);
            } else {
                client.publish(listRaw());
            }
        } catch (Exception ex) {
            log.warn("nacos config unavailable, using defaults: {}", ex.getMessage());
        }
    }

    public void mergeRemote(List<AppConfig> items) {
        for (AppConfig item : items) {
            String key = item.configKey();
            if (key == null || key.isBlank() || key.startsWith("agent.prompt.")) {
                continue;
            }
            AppConfig existing = configs.get(key);
            boolean secret = item.isSecret()
                    || (existing != null && existing.isSecret())
                    || NacosConfigClient.isSecretKey(key);
            configs.put(key, new AppConfig(key, item.configValue() == null ? "" : item.configValue(),
                    item.valueType() == null || item.valueType().isBlank()
                            ? (existing == null ? "string" : existing.valueType()) : item.valueType(),
                    item.description() == null || item.description().isBlank()
                            ? (existing == null ? "" : existing.description()) : item.description(),
                    item.domain() == null || item.domain().isBlank()
                            ? (existing == null ? domainFor(key) : existing.domain())
                            : item.domain(),
                    secret, GoTime.format(LocalDateTime.now())));
        }
        applyToProperties();
    }

    public void applyToProperties() {
        String origins = value("http.cors.allowed_origins", "*");
        properties.getWeb().getCors().setAllowedOrigins(csv(origins));
        properties.getWeb().setTrustedProxies(csv(value("http.trusted_proxy_cidrs", "")));
        properties.getWeb().setTrustAllProxies(Boolean.parseBoolean(
                value("http.trust_all_proxies", "false")));
        properties.getFiles().setMaxUploadBytes(longValue("files.max_upload_bytes",
                properties.getFiles().getMaxUploadBytes()));
        properties.getWeb().getRateLimit().setEnabled(Boolean.parseBoolean(
                value("rate_limit.enabled", String.valueOf(
                        properties.getWeb().getRateLimit().isEnabled()))));
        properties.getWeb().getRateLimit().setLimit((int) longValue("rate_limit.limit",
                properties.getWeb().getRateLimit().getLimit()));
        properties.getWeb().getRateLimit().setWindowSeconds(longValue("rate_limit.window_seconds",
                properties.getWeb().getRateLimit().getWindowSeconds()));
        properties.getRetrieval().getVector().setEnabled(Boolean.parseBoolean(
                value("vector.enabled", String.valueOf(
                        properties.getRetrieval().getVector().isEnabled()))));
        rateLimitFilter.ifAvailable(com.xzxg.shop.common.web.RateLimitFilter::refresh);
    }

    public String stringValue(String key, String fallback) {
        AppConfig item = configs.get(key);
        return item == null || item.configValue() == null || item.configValue().isBlank()
                ? fallback : item.configValue();
    }

    private String value(String key, String fallback) {
        AppConfig item = configs.get(key);
        return item == null || item.configValue() == null || item.configValue().isBlank()
                ? fallback : item.configValue();
    }

    private long longValue(String key, long fallback) {
        try {
            return Long.parseLong(value(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    public List<AppConfig> list() {
        return listRaw().stream().map(this::masked).toList();
    }

    public List<AppConfig> listRaw() {
        List<AppConfig> items = new ArrayList<>(configs.values());
        items.sort(Comparator.comparing(AppConfig::configKey));
        return items;
    }

    public Map<String, String> snapshot() {
        Map<String, String> values = new LinkedHashMap<>();
        for (AppConfig item : listRaw()) {
            values.put(item.configKey(), item.configValue());
        }
        return values;
    }

    private void publish() {
        NacosConfigClient client = nacos.getIfAvailable();
        if (client == null) {
            return;
        }
        try {
            client.publish(listRaw());
        } catch (Exception ex) {
            log.warn("nacos publish failed: {}", ex.getMessage());
        }
    }

    public AppConfig update(String key, String value, String valueType, String description,
                            Boolean isSecret) {
        boolean secret = isSecret != null ? isSecret : isSecretKey(key);
        AppConfig existing = configs.get(key);
        String effectiveValue = value;
        if (secret && "******".equals(value) && existing != null) {
            effectiveValue = existing.configValue();
        }
        AppConfig item = new AppConfig(key, effectiveValue,
                valueType == null || valueType.isBlank() ? "string" : valueType,
                description == null ? "" : description, domainFor(key), secret,
                GoTime.format(LocalDateTime.now()));
        configs.put(key, item);
        applyToProperties();
        publish();
        return new AppConfig(item.configKey(), mask(item), item.valueType(), item.description(),
                item.domain(), item.isSecret(), item.updatedAt());
    }

    private void put(String key, String value, String type, String description, String domain,
                     boolean secret) {
        configs.put(key, new AppConfig(key, value, type, description, domain, secret,
                GoTime.format(LocalDateTime.now())));
    }

    private static boolean isSecretKey(String key) {
        return NacosConfigClient.isSecretKey(key);
    }

    private static String domainFor(String key) {
        return NacosConfigClient.domainForKey(key);
    }

    private static String mask(AppConfig item) {
        return item.isSecret() && item.configValue() != null && !item.configValue().isEmpty()
                ? "******" : item.configValue();
    }

    public AppConfig masked(AppConfig item) {
        return new AppConfig(item.configKey(), mask(item), item.valueType(), item.description(),
                item.domain(), item.isSecret(), item.updatedAt());
    }
}
