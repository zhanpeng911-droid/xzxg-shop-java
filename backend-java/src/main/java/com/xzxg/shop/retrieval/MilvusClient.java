package com.xzxg.shop.retrieval;

import com.xzxg.shop.config.ShopProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal Milvus 2.5 REST client: search plus collection describe/stats. Index
 * bootstrap is intentionally not automatic; see KNOWN_DIFFERENCES.md.
 */
@Component
public class MilvusClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ShopProperties.Vector config;
    private final ObjectMapper objectMapper;

    public MilvusClient(ShopProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getRetrieval().getVector();
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return config.isEnabled();
    }

    public List<SearchHit> search(String collection, float[] vector, String idField,
                                  List<String> outputFields, int limit) throws Exception {
        if (!enabled()) {
            throw new IllegalStateException("vector client disabled");
        }
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("empty vector");
        }
        int effectiveLimit = limit > 0 ? limit : config.getTopN();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dbName", config.getDatabase());
        payload.put("collectionName", collection);
        payload.put("data", List.of(vector));
        payload.put("annsField", "embedding");
        payload.put("limit", effectiveLimit);
        payload.put("outputFields", outputFields);
        payload.put("searchParams", Map.of("metricType", "COSINE"));
        JsonNode root = post("/v2/vectordb/entities/search", payload);
        JsonNode data = root.get("data");
        List<SearchHit> hits = new ArrayList<>();
        if (data == null || !data.isArray()) {
            return hits;
        }
        for (JsonNode row : data) {
            String id = text(row.get(idField));
            if (id.isEmpty()) {
                id = text(row.get("id"));
            }
            if (id.isEmpty()) {
                continue;
            }
            double score = number(row.get("distance"));
            if (config.getMinScore() > 0 && score < config.getMinScore()) {
                continue;
            }
            hits.add(new SearchHit(id, score, row));
        }
        return hits;
    }

    public JsonNode describe(String collection) throws Exception {
        return post("/v2/vectordb/collections/describe", Map.of(
                "dbName", config.getDatabase(),
                "collectionName", collection));
    }

    public JsonNode stats(String collection) throws Exception {
        return post("/v2/vectordb/collections/get_stats", Map.of(
                "dbName", config.getDatabase(),
                "collectionName", collection));
    }

    private JsonNode post(String path, Object payload) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(config.getMilvusAddress().replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (config.getMilvusToken() != null && !config.getMilvusToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.getMilvusToken());
        }
        HttpResponse<String> response = HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("milvus status " + response.statusCode()
                    + ": " + response.body().trim());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode code = root.get("code");
        if (code != null && code.asInt() != 0 && code.asInt() != 200) {
            throw new IllegalStateException("milvus code " + code.asInt() + ": "
                    + text(root.get("message")));
        }
        return root;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString();
    }

    private static double number(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        try {
            return Double.parseDouble(node.asString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public record SearchHit(String id, double score, JsonNode fields) {
    }
}
