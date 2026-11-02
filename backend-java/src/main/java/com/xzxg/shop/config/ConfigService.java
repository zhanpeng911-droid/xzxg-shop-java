package com.xzxg.shop.config;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.domain.AppConfig;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory config center mirroring the Go defaults. Nacos is not wired yet, so
 * updates are process-local and do not change already-created beans; the plan's
 * Nacos adapter remains a documented gap.
 */
@Service
public class ConfigService {

    private final Map<String, AppConfig> configs = new ConcurrentHashMap<>();

    public ConfigService() {
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

    public List<AppConfig> list() {
        List<AppConfig> items = new ArrayList<>(configs.values());
        items.sort(Comparator.comparing(AppConfig::configKey));
        return items;
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
        return new AppConfig(item.configKey(), mask(item), item.valueType(), item.description(),
                item.domain(), item.isSecret(), item.updatedAt());
    }

    private void put(String key, String value, String type, String description, String domain,
                     boolean secret) {
        configs.put(key, new AppConfig(key, value, type, description, domain, secret,
                GoTime.format(LocalDateTime.now())));
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("key") || lower.contains("secret");
    }

    private static String domainFor(String key) {
        return key.startsWith("ai.") ? "app" : "infra";
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
