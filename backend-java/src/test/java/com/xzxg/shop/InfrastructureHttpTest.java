package com.xzxg.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the real servlet stack and checks the infrastructure contract against the
 * Go baseline: no response wrapper, 24-hex request ids, wildcard CORS with an
 * empty 204 preflight, and the error root object.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InfrastructureHttpTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Test
    void healthReturnsRootObjectWithoutWrapper() throws Exception {
        HttpResponse<String> response = send(get("/api/v1/health").build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(response.headers().firstValue("X-Request-ID"))
                .hasValueSatisfying(id -> assertThat(id).matches("[0-9a-f]{24}"));
    }

    @Test
    void echoesSafeClientRequestId() throws Exception {
        HttpResponse<String> response =
                send(get("/api/v1/health").header("X-Request-ID", "abc-123_ok").build());

        assertThat(response.headers().firstValue("X-Request-ID")).contains("abc-123_ok");
    }

    @Test
    void replacesUnreasonablyLongRequestId() throws Exception {
        String tooLong = "a".repeat(300);
        HttpResponse<String> response =
                send(get("/api/v1/health").header("X-Request-ID", tooLong).build());

        assertThat(response.headers().firstValue("X-Request-ID"))
                .hasValueSatisfying(id -> {
                    assertThat(id).isNotEqualTo(tooLong);
                    assertThat(id).matches("[0-9a-f]{24}");
                });
    }

    @Test
    void preflightReturnsEmpty204WithWildcardCors() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/anything"))
                .header("Origin", "http://localhost:5173")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers"))
                .contains("Content-Type, Accept, Authorization");
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods"))
                .contains("GET, POST, PATCH, DELETE, OPTIONS");
        assertThat(response.headers().firstValue("X-Request-ID")).isEmpty();
    }

    @Test
    void unknownPathReturnsErrorRootObject() throws Exception {
        HttpResponse<String> response =
                send(get("/api/v1/does-not-exist").header("X-Request-ID", "req-404").build());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"not_found\"");
        assertThat(response.body()).contains("\"request_id\":\"req-404\"");
    }

    @Test
    void unsupportedMethodReturnsErrorRootObject() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/health"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).contains("\"code\":\"method_not_allowed\"");
    }

    @Test
    void oversizedBodyIsRejectedBeforeRouting() throws Exception {
        byte[] body = new byte[2048];
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/anything"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(10))
                .build());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"payload_too_large\"");
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .GET();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
