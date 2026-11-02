package com.xzxg.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** P6 guide suggestions, eval endpoints, speech disabled paths, and dashboard. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql-it")
class P6FlowIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void guideEvalAndSpeechBehavior() throws Exception {
        String userToken = login("user", "user123456");
        String adminToken = login("admin", "admin123456");

        JsonNode suggestions = json(call("POST", "/api/v1/agent/guide-suggestions", userToken,
                "{\"page\":\"products\",\"context\":{\"keyword\":\"拍照手机\"},\"limit\":3}"));
        assertThat(suggestions.get("items").size()).isEqualTo(3);
        assertThat(suggestions.get("items").get(0).get("question").asText())
                .isEqualTo("帮我选拍照手机");
        assertThat(suggestions.get("items").get(0).get("question").asText()
                .codePointCount(0, suggestions.get("items").get(0).get("question").asText().length()))
                .isLessThanOrEqualTo(10);

        HttpResponse<String> badPage = call("POST", "/api/v1/agent/guide-suggestions",
                userToken, "{\"page\":\"unknown\"}");
        assertThat(badPage.statusCode()).isEqualTo(400);
        assertThat(json(badPage).get("code").asText()).isEqualTo("bad_page");

        JsonNode intent = json(call("POST", "/api/v1/eval/intent", adminToken,
                "{\"query\":\"推荐拍照手机\"}"));
        assertThat(intent.get("route").asText()).isEqualTo("guide");
        assertThat(intent.get("intent").asText()).isEqualTo("product_recommend");
        JsonNode nonGuide = json(call("POST", "/api/v1/eval/intent", adminToken,
                "{\"query\":\"看看我的购物车\"}"));
        assertThat(nonGuide.get("route").asText()).isEqualTo("non_guide");

        JsonNode rag = json(call("POST", "/api/v1/eval/rag", adminToken,
                "{\"keyword\":\"X Phone\",\"top_k\":3}"));
        assertThat(rag.get("items").size()).isPositive();
        assertThat(rag.get("items").get(0).get("chunkId").asText()).isNotEmpty();

        JsonNode products = json(call("POST", "/api/v1/eval/products", adminToken,
                "{\"query\":\"手机\",\"limit\":5}"));
        assertThat(products.get("relevance_status").asText()).isEqualTo("ok");
        assertThat(products.get("product_ids").size()).isPositive();

        HttpResponse<String> evalForbidden = call("POST", "/api/v1/eval/intent", userToken,
                "{\"query\":\"推荐手机\"}");
        assertThat(evalForbidden.statusCode()).isEqualTo(403);

        JsonNode dashboard = json(call("GET", "/api/v1/admin/evals", adminToken, null));
        assertThat(dashboard.get("datasets").isArray()).isTrue();
        assertThat(dashboard.get("reports").isArray()).isTrue();
        assertThat(dashboard.get("tools").size()).isEqualTo(7);

        JsonNode configs = json(call("GET", "/api/v1/admin/configs", adminToken, null));
        assertThat(configs.get("total").asInt()).isPositive();
        JsonNode updatedConfig = json(call("PATCH",
                "/api/v1/admin/configs/http.cors.allowed_origins", adminToken,
                "{\"value\":\"http://example.com\",\"value_type\":\"string\"}"));
        assertThat(updatedConfig.get("config_value").asText()).isEqualTo("http://example.com");

        JsonNode ttsConfig = json(call("GET", "/api/v1/speech/tts/config", userToken, null));
        assertThat(ttsConfig.get("enabled").asBoolean()).isFalse();
        assertThat(ttsConfig.get("provider").asText()).isEqualTo("xunfei");
        assertThat(ttsConfig.get("voice").asText()).isEqualTo("xiaoyan");
        assertThat(ttsConfig.get("max_text_chars").asInt()).isEqualTo(800);

        HttpResponse<String> tts = call("POST", "/api/v1/speech/tts", userToken,
                "{\"text\":\"你好\"}");
        assertThat(tts.statusCode()).isEqualTo(501);
        assertThat(json(tts).get("code").asText()).isEqualTo("tts_not_enabled");

        HttpResponse<String> emptyTts = call("POST", "/api/v1/speech/tts", userToken, "{\"text\":\"\"}");
        assertThat(emptyTts.statusCode()).isEqualTo(501);
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
