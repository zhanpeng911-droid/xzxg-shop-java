package com.xzxg.shop.agent;

import com.xzxg.shop.config.ShopProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** OpenAI-compatible chat completions client. */
@Component
public class LlmClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ShopProperties.Agent config;
    private final ObjectMapper objectMapper;

    public LlmClient(ShopProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getAgent();
        this.objectMapper = objectMapper;
    }

    public record Message(String role, String content) {
    }

    public boolean enabled() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    public String smallModel() {
        return config.getSmallModel();
    }

    public String largeModel() {
        return config.getLargeModel();
    }

    public String complete(String model, List<Message> messages, double temperature) throws Exception {
        if (!enabled()) {
            throw new IllegalStateException("llm disabled: missing api key");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model == null || model.isBlank() ? config.getSmallModel() : model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        payload.put("enable_thinking", config.isEnableThinking());
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("llm status " + response.statusCode() + ": "
                    + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("llm response missing content");
        }
        return content.asString();
    }
}
