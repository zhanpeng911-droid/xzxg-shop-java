package com.xzxg.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.xzxg.shop.domain.Citation;
import com.xzxg.shop.knowledge.KnowledgeService;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * P4 knowledge ingestion, dedupe, keyword retrieval, and image-search degradation.
 * File upload itself needs a running MinIO and is covered separately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql-it")
class P4FlowIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KnowledgeService knowledge;

    @Test
    void ingestDedupeSearchAndImageFallback() throws Exception {
        String merchantToken = login("merchant", "merchant123456");
        String userToken = login("user", "user123456");
        String adminToken = login("admin", "admin123456");

        String content = "集成知识词：磁吸充电支持 15W，适配全部在售手机。";
        JsonNode first = json(call("POST", "/api/v1/merchant/documents", merchantToken,
                "{\"title\":\"磁吸充电说明\",\"doc_type\":\"product_detail\",\"content\":\""
                        + content + "\"}"));
        assertThat(first.get("status").asText()).isEqualTo("indexed");
        assertThat(first.get("chunk_count").asInt()).isPositive();
        String documentId = first.get("document_id").asText();

        JsonNode duplicate = json(call("POST", "/api/v1/merchant/documents", merchantToken,
                "{\"title\":\"磁吸充电说明\",\"doc_type\":\"product_detail\",\"content\":\""
                        + content + "\"}"));
        assertThat(duplicate.get("document_id").asText()).isEqualTo(documentId);

        HttpResponse<String> ingest = call("POST", "/api/v1/merchant/unstructured-ingestions",
                merchantToken, "{\"title\":\"网页入库\",\"source_type\":\"html\",\"html\":"
                        + "\"<html><body><h1>网页入库词</h1><p>支持门店自提</p></body></html>\"}");
        assertThat(ingest.statusCode()).isEqualTo(201);
        JsonNode ingestJson = json(ingest);
        assertThat(ingestJson.get("duplicate").asBoolean()).isFalse();
        assertThat(ingestJson.get("document").get("doc_type").asText()).isEqualTo("web_article");
        assertThat(ingestJson.get("text_runes").asInt()).isPositive();
        String ingestDocumentId = ingestJson.get("document").get("document_id").asText();

        JsonNode ingestDuplicate = json(call("POST",
                "/api/v1/merchant/unstructured-ingestions", merchantToken,
                "{\"title\":\"网页入库\",\"source_type\":\"html\",\"html\":"
                        + "\"<html><body><h1>网页入库词</h1><p>支持门店自提</p></body></html>\"}"));
        assertThat(ingestDuplicate.get("duplicate").asBoolean()).isTrue();
        assertThat(ingestDuplicate.get("document").get("document_id").asText())
                .isEqualTo(ingestDocumentId);

        JsonNode documents = json(call("GET", "/api/v1/merchant/documents?page_size=100",
                merchantToken, null));
        assertThat(documents.get("total").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(json(call("GET", "/api/v1/admin/documents", adminToken, null))
                .get("total").asInt()).isGreaterThanOrEqualTo(2);

        List<Citation> citations = knowledge.search("磁吸充电", 5);
        assertThat(citations).isNotEmpty();
        assertThat(citations.get(0).snippet()).contains("磁吸充电");

        HttpResponse<String> adminMissingMerchant = call("POST",
                "/api/v1/admin/unstructured-ingestions", adminToken,
                "{\"source_type\":\"text\",\"content\":\"内容\"}");
        assertThat(adminMissingMerchant.statusCode()).isEqualTo(400);
        assertThat(json(adminMissingMerchant).get("code").asText()).isEqualTo("empty_merchant_id");

        HttpResponse<String> emptyIngest = call("POST",
                "/api/v1/merchant/unstructured-ingestions", merchantToken,
                "{\"source_type\":\"text\"}");
        assertThat(emptyIngest.statusCode()).isEqualTo(400);
        assertThat(json(emptyIngest).get("code").asText()).isEqualTo("parse_ingestion_failed");

        HttpResponse<String> emptyImage = call("POST", "/api/v1/search/image", userToken, "{}");
        assertThat(emptyImage.statusCode()).isEqualTo(400);
        assertThat(json(emptyImage).get("code").asText()).isEqualTo("empty_image");

        HttpResponse<String> missingFile = call("POST", "/api/v1/search/image", userToken,
                "{\"file_id\":\"file_missing\"}");
        assertThat(missingFile.statusCode()).isEqualTo(404);
        assertThat(json(missingFile).get("code").asText()).isEqualTo("file_not_found");

        Path image = Files.createTempFile("p4-image", ".png");
        BufferedImage buffered = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                buffered.setRGB(x, y, 0xFFFF0000);
            }
        }
        ImageIO.write(buffered, "png", image.toFile());
        String escaped = image.toString().replace("\\", "\\\\");
        HttpResponse<String> fallback = call("POST", "/api/v1/search/image", userToken,
                "{\"image_url\":\"" + escaped + "\",\"top_k\":3}");
        assertThat(fallback.statusCode()).isEqualTo(200);
        JsonNode fallbackJson = json(fallback);
        assertThat(fallbackJson.get("relevance_status").asText()).isEqualTo("no_match");
        assertThat(fallbackJson.get("match_status").asText())
                .isEqualTo("image_vector_index_unavailable");
        assertThat(fallbackJson.get("items").size()).isZero();
        assertThat(fallbackJson.get("durations").get("embedding_ms").asLong()).isGreaterThanOrEqualTo(0);
    }

    private String login(String username, String password) throws Exception {
        HttpResponse<String> response = call("POST", "/api/v1/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).get("token").asText();
    }

    private HttpResponse<String> call(String method, String path, String token, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }
}
