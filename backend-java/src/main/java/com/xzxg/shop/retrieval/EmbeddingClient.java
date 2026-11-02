package com.xzxg.shop.retrieval;

import com.xzxg.shop.config.ShopProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** OpenAI-compatible text embedding client. */
@Component
public class EmbeddingClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ShopProperties.Embedding config;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(ShopProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getRetrieval().getEmbedding();
        this.objectMapper = objectMapper;
    }

    public boolean configured() {
        return config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                && config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    public List<float[]> embed(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!configured()) {
            throw new IllegalStateException("embedding base url or api key is empty");
        }
        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "model", config.getModel(),
                "input", texts));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(config.getBaseUrl().replaceAll("/+$", "") + "/embeddings"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("embedding status " + response.statusCode()
                    + ": " + response.body().trim());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != texts.size()) {
            throw new IllegalStateException("embedding count mismatch");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < data.size(); i++) {
            JsonNode embedding = data.get(i).get("embedding");
            float[] vector = new float[embedding.size()];
            for (int j = 0; j < embedding.size(); j++) {
                vector[j] = (float) embedding.get(j).asDouble();
            }
            if (vector.length == 0) {
                throw new IllegalStateException("empty embedding at index " + i);
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
