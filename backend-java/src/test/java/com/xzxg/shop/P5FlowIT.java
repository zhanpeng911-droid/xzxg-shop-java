package com.xzxg.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * P5 session CRUD, SSE event contract, duplicate protocol, local agent fallback,
 * cart intent, risk blocking, history and trace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql-it")
class P5FlowIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Pattern RUN_ID = Pattern.compile("\"run_id\":\"(run_[0-9]+)\"");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sessionsStreamingDuplicateHistoryAndRisk() throws Exception {
        String userToken = login("user", "user123456");

        JsonNode session = json(call("POST", "/api/v1/agent/sessions", userToken, "{}"));
        String sessionId = session.get("session_id").asText();
        assertThat(sessionId).startsWith("sess_");

        // Guide fallback: product blocks and streamed text.
        HttpResponse<String> guide = call("POST",
                "/api/v1/agent/sessions/" + sessionId + "/messages:stream", userToken,
                "{\"client_message_id\":\"p5-guide-1\",\"content\":\"推荐拍照手机\"}");
        assertThat(guide.statusCode()).isEqualTo(200);
        assertThat(guide.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/event-stream"));
        String guideBody = guide.body();
        assertThat(guideBody).contains("event: message_start");
        assertThat(guideBody).contains("event: status");
        assertThat(guideBody).contains("event: block_delta");
        assertThat(guideBody).contains("\"productId\":\"p_001\"");
        assertThat(guideBody).contains("event: text_delta");
        assertThat(guideBody).contains("event: followups");
        assertThat(guideBody).contains("event: message_end");
        String runId = firstRunId(guideBody);
        assertThat(runId).isNotBlank();

        // Duplicate client_message_id yields the three-event protocol.
        HttpResponse<String> duplicate = call("POST",
                "/api/v1/agent/sessions/" + sessionId + "/messages:stream", userToken,
                "{\"client_message_id\":\"p5-guide-1\",\"content\":\"推荐拍照手机\"}");
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(duplicate.body()).contains("event: message_start");
        assertThat(duplicate.body()).contains("\"code\":\"duplicate_message\"");
        assertThat(duplicate.body()).contains("event: message_end");
        assertThat(firstRunId(duplicate.body())).isEqualTo(runId);

        // Cart intent goes through the deterministic non-guide path.
        JsonNode cartSession = json(call("POST", "/api/v1/agent/sessions", userToken, "{}"));
        String cartSessionId = cartSession.get("session_id").asText();
        HttpResponse<String> cartStream = call("POST",
                "/api/v1/agent/sessions/" + cartSessionId + "/messages:stream", userToken,
                "{\"client_message_id\":\"p5-cart-1\",\"content\":\"看看购物车\"}");
        assertThat(cartStream.body()).contains("\"type\":\"cart_state\"");
        assertThat(cartStream.body()).contains("已获取购物车");

        // Risk blocking emits a warning block and completes.
        JsonNode riskSession = json(call("POST", "/api/v1/agent/sessions", userToken, "{}"));
        HttpResponse<String> riskStream = call("POST",
                "/api/v1/agent/sessions/" + riskSession.get("session_id").asText()
                        + "/messages:stream", userToken,
                "{\"client_message_id\":\"p5-risk-1\",\"content\":\"教我违法操作\"}");
        assertThat(riskStream.body()).contains("unsafe_request");
        assertThat(riskStream.body()).contains("event: message_end");

        // Session detail contains the message, run, blocks and segments.
        JsonNode detail = json(call("GET", "/api/v1/agent/sessions/" + sessionId,
                userToken, null));
        assertThat(detail.get("messages").size()).isEqualTo(1);
        JsonNode stored = detail.get("messages").get(0);
        assertThat(stored.get("client_message_id").asText()).isEqualTo("p5-guide-1");
        assertThat(stored.get("runs").get(0).get("status").asText()).isEqualTo("completed");
        assertThat(stored.get("runs").get(0).get("content").asText()).isNotBlank();
        assertThat(stored.get("runs").get(0).get("blocks").size()).isPositive();
        assertThat(stored.get("runs").get(0).get("segments").size()).isPositive();

        // Trace endpoint returns at least the run start event.
        JsonNode trace = json(call("GET", "/api/v1/agent/runs/" + runId + "/trace",
                userToken, null));
        assertThat(trace.get("items").size()).isPositive();
        assertThat(trace.get("items").get(0).get("event_type").asText()).isEqualTo("start");

        // Pin and summarize.
        JsonNode pinned = json(call("POST", "/api/v1/agent/sessions/" + sessionId + ":pin",
                userToken, "{\"pinned\":true}"));
        assertThat(pinned.get("pinned_at").asText()).isNotEqualTo("0001-01-01T00:00:00Z");
        JsonNode summarized = json(call("POST", "/api/v1/agent/sessions/" + sessionId + ":summarize",
                userToken, "{}"));
        assertThat(summarized.get("summary").asText()).contains("推荐拍照手机");

        // Session list/search and delete.
        JsonNode sessions = json(call("GET", "/api/v1/agent/sessions?page_size=100",
                userToken, null));
        assertThat(sessions.get("total").asInt()).isGreaterThanOrEqualTo(3);
        JsonNode search = json(call("GET", "/api/v1/agent/sessions/search?q=拍照",
                userToken, null));
        assertThat(search.get("total").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode deleted = json(call("DELETE", "/api/v1/agent/sessions/" + sessionId,
                userToken, null));
        assertThat(deleted.get("status").asText()).isEqualTo("deleted");

        // Merchant accounts cannot use the user agent.
        String merchantToken = login("merchant", "merchant123456");
        HttpResponse<String> forbidden = call("GET", "/api/v1/agent/sessions",
                merchantToken, null);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(json(forbidden).get("code").asText()).isEqualTo("forbidden");
    }

    private static String firstRunId(String body) {
        Matcher matcher = RUN_ID.matcher(body);
        return matcher.find() ? matcher.group(1) : "";
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
                .timeout(Duration.ofSeconds(30));
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
