package com.xzxg.shop.config;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.domain.AppConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Nacos v1 config API client compatible with the Go implementation:
 * four domain data ids, {@code {"configs":[...]}} documents (prompt data id uses
 * {@code {"prompts":[...]}}), and legacy plain key/value objects.
 */
@Component
@ConditionalOnProperty(prefix = "shop.nacos", name = "enabled", havingValue = "true")
public class NacosConfigClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Map<String, String> DATA_IDS = Map.of(
            "app", "xzxg-shop-app-config.json",
            "rag", "xzxg-shop-rag-config.json",
            "infra", "xzxg-shop-infra-config.json",
            "prompt", "xzxg-shop-prompt-config.json");

    private final ShopProperties.Nacos config;
    private final ObjectMapper objectMapper;
    private volatile String accessToken = "";

    public NacosConfigClient(ShopProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getNacos();
        this.objectMapper = objectMapper;
    }

    public List<AppConfig> load() throws Exception {
        authenticate();
        Map<String, AppConfig> merged = new LinkedHashMap<>();
        for (String dataId : readDataIds()) {
            for (AppConfig item : readDocument(dataId)) {
                merged.put(item.configKey(), item);
            }
        }
        List<AppConfig> items = new ArrayList<>(merged.values());
        items.sort(Comparator.comparing(AppConfig::configKey));
        return items;
    }

    public void publish(List<AppConfig> items) throws Exception {
        authenticate();
        Map<String, List<AppConfig>> byDomain = new LinkedHashMap<>();
        for (AppConfig item : items) {
            String domain = item.domain() == null || item.domain().isBlank()
                    ? domainForKey(item.configKey()) : item.domain();
            byDomain.computeIfAbsent(domain, key -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<AppConfig>> entry : byDomain.entrySet()) {
            publishDocument(dataIdForDomain(entry.getKey()), entry.getValue());
        }
    }

    private List<String> readDataIds() {
        List<String> ids = new ArrayList<>();
        ids.add(config.getDataId());
        for (String domain : List.of("app", "infra", "rag")) {
            String dataId = dataIdForDomain(domain);
            if (!ids.contains(dataId)) {
                ids.add(dataId);
            }
        }
        return ids;
    }

    private List<AppConfig> readDocument(String dataId) throws Exception {
        String url = base() + "/nacos/v1/cs/configs?dataId=" + encode(dataId)
                + "&group=" + encode(config.getGroup())
                + (config.getNamespace().isBlank() ? "" : "&tenant=" + encode(config.getNamespace()))
                + (accessToken.isBlank() ? "" : "&accessToken=" + encode(accessToken));
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404
                || response.body().toLowerCase(Locale.ROOT).contains("config data not exist")) {
            return List.of();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("nacos load status " + response.statusCode());
        }
        if (response.body().isBlank()) {
            return List.of();
        }
        return parseDocument(response.body(), dataId);
    }

    private List<AppConfig> parseDocument(String raw, String dataId) {
        List<AppConfig> items = new ArrayList<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (RuntimeException ex) {
            return items;
        }
        if (root.has("configs") && root.get("configs").isArray()) {
            for (JsonNode node : root.get("configs")) {
                String key = node.path("config_key").asString("");
                if (key.isBlank()) {
                    continue;
                }
                String domain = node.path("domain").asString("");
                items.add(new AppConfig(key, node.path("config_value").asString(""),
                        node.path("value_type").asString("string"),
                        node.path("description").asString(""),
                        domain.isBlank() ? domainForKey(key) : domain,
                        node.path("is_secret").asBoolean(isSecretKey(key)),
                        node.path("updated_at").asString(GoTime.format(LocalDateTime.now()))));
            }
            return items;
        }
        if (root.has("prompts") && root.get("prompts").isArray()) {
            for (JsonNode node : root.get("prompts")) {
                String key = node.path("prompt_key").asString("");
                if (key.isBlank()) {
                    continue;
                }
                items.add(new AppConfig(key, node.path("content").asString(""),
                        "text", node.path("description").asString(""), "prompt", false,
                        node.path("updated_at").asString(GoTime.format(LocalDateTime.now()))));
            }
            return items;
        }
        if (root.isObject()) {
            root.forEachEntry((key, value) -> {
                if (value.isString() || value.isNumber() || value.isBoolean()) {
                    items.add(new AppConfig(key, value.asString(""), "string", "",
                            domainForKey(key), isSecretKey(key),
                            GoTime.format(LocalDateTime.now())));
                }
            });
        }
        return items;
    }

    private void publishDocument(String dataId, List<AppConfig> items) throws Exception {
        boolean prompt = "prompt".equals(domainOf(dataId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AppConfig item : items.stream()
                .sorted(Comparator.comparing(AppConfig::configKey)).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (prompt) {
                row.put("prompt_key", item.configKey());
                row.put("title", item.description());
                row.put("content", item.configValue());
                row.put("status", "active");
                row.put("description", item.description());
                row.put("updated_at", item.updatedAt());
            } else {
                row.put("config_key", item.configKey());
                row.put("config_value", item.configValue());
                row.put("value_type", item.valueType());
                row.put("description", item.description());
                row.put("domain", item.domain() == null ? domainForKey(item.configKey())
                        : item.domain());
                row.put("is_secret", item.isSecret());
                row.put("updated_at", item.updatedAt());
            }
            rows.add(row);
        }
        Map<String, Object> document = prompt ? Map.of("prompts", rows) : Map.of("configs", rows);
        String content = objectMapper.writeValueAsString(document);
        StringBuilder form = new StringBuilder();
        form.append("dataId=").append(encode(dataId));
        form.append("&group=").append(encode(config.getGroup()));
        if (!config.getNamespace().isBlank()) {
            form.append("&tenant=").append(encode(config.getNamespace()));
        }
        form.append("&content=").append(encode(content));
        form.append("&type=json");
        if (!accessToken.isBlank()) {
            form.append("&accessToken=").append(encode(accessToken));
        }
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base() + "/nacos/v1/cs/configs"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || !"true".equalsIgnoreCase(response.body().trim())) {
            throw new IllegalStateException("nacos publish failed: " + response.statusCode()
                    + " " + response.body());
        }
    }

    private void authenticate() throws Exception {
        if (config.getUsername().isBlank() || config.getPassword().isBlank()
                || !accessToken.isEmpty()) {
            return;
        }
        String form = "username=" + encode(config.getUsername())
                + "&password=" + encode(config.getPassword());
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/nacos/v1/auth/login"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            accessToken = objectMapper.readTree(response.body()).path("accessToken").asString("");
        }
    }

    private static String domainOf(String dataId) {
        for (Map.Entry<String, String> entry : DATA_IDS.entrySet()) {
            if (entry.getValue().equals(dataId)) {
                return entry.getKey();
            }
        }
        return "app";
    }

    static String dataIdForDomain(String domain) {
        return DATA_IDS.getOrDefault(domain, DATA_IDS.get("app"));
    }

    static String domainForKey(String key) {
        if (key.startsWith("agent.prompt.")) {
            return "prompt";
        }
        if (key.startsWith("retrieval.")) {
            return "rag";
        }
        if (key.startsWith("vector.") || key.startsWith("milvus.")
                || key.startsWith("embedding.") || key.startsWith("image_embedding.")) {
            return "infra";
        }
        return "app";
    }

    static boolean isSecretKey(String key) {
        String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return lower.contains("api_key") || lower.contains("api-key")
                || lower.contains("api_secret") || lower.contains("api-secret")
                || lower.contains("secret") || lower.contains("password")
                || lower.contains("access_key") || lower.contains("credential");
    }

    private String base() {
        return config.getServerAddr().replaceAll("/+$", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
