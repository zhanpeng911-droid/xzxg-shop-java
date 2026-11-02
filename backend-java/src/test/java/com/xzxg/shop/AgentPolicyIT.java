package com.xzxg.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stubbed-model tests for tool/skill whitelists, product-ID whitelist, and real
 * cancellation. No real API key is required.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shop.agent.api-key=stub",
                "shop.agent.small-model=stub-model",
                "shop.agent.sse-heartbeat-ms=200"
        })
@ActiveProfiles("mysql-it")
class AgentPolicyIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final HttpServer STUB = startStub();
    private static final ObjectMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();

    @DynamicPropertySource
    static void agentProperties(DynamicPropertyRegistry registry) {
        registry.add("shop.agent.base-url",
                () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void policyWhitelistsAndCancellation() throws Exception {
        String token = registerUser();

        // 1. Model tries to add a guessed product without a prior search result.
        HttpResponse<String> guessAdd = call("POST", streamPath(createSession(token)), token,
                "{\"client_message_id\":\"policy-guess\",\"content\":\"POLICY_GUESS_ADD 帮我加购\"}");
        assertThat(guessAdd.body()).doesNotContain("\"type\":\"cart_state\"");
        assertThat(cartQuantity(token, "p_001")).isZero();

        // 2. Guide intent cannot call checkout even if the model asks for it.
        HttpResponse<String> denyCheckout = call("POST",
                streamPath(createSession(token)), token,
                "{\"client_message_id\":\"policy-checkout\",\"content\":\"POLICY_DENY_CHECKOUT 推荐拍照手机\"}");
        assertThat(denyCheckout.body()).doesNotContain("\"type\":\"order_summary\"");
        assertThat(guessAdd.body()).doesNotContain("已创建");
        assertThat(orderCount(token)).isZero();

        // 3. Search results populate the product whitelist, so the follow-up add succeeds.
        HttpResponse<String> allowAdd = call("POST", streamPath(createSession(token)), token,
                "{\"client_message_id\":\"policy-allow\",\"content\":\"POLICY_ALLOW_ADD 帮我加购 p_001\"}");
        assertThat(allowAdd.statusCode()).isEqualTo(200);
        assertThat(allowAdd.body()).contains("\"type\":\"cart_state\"");
        assertThat(count(allowAdd.body(), "event: text_delta")).isGreaterThan(1);
        assertThat(allowAdd.body()).contains("已加入购物车");
        assertThat(cartQuantity(token, "p_001")).isEqualTo(1);

        // 4. Cancellation stops the in-flight model call and keeps status canceled.
        String sessionId = createSession(token);
        CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return call("POST", streamPath(sessionId), token,
                        "{\"client_message_id\":\"policy-cancel\",\"content\":\"POLICY_CANCEL 推荐拍照手机\"}");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        Thread.sleep(1200);
        String runId = runIdOf(sessionId, token);
        assertThat(runId).isNotBlank();
        HttpResponse<String> cancel = call("POST", "/api/v1/agent/runs/" + runId + ":cancel",
                token, null);
        assertThat(cancel.statusCode()).isEqualTo(200);
        HttpResponse<String> streamed = pending.get(6, TimeUnit.SECONDS);
        assertThat(streamed.body()).contains("\"code\":\"canceled\"");
        JsonNode detail = json(call("GET", "/api/v1/agent/sessions/" + sessionId, token, null));
        assertThat(detail.get("messages").get(0).get("runs").get(0).get("status").asText())
                .isEqualTo("canceled");
    }

    @Test
    void cancellationStopsMidStreamPause() throws Exception {
        String token = registerUser();
        String sessionId = createSession(token);
        long startedAt = System.nanoTime();
        CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return call("POST", streamPath(sessionId), token,
                        "{\"client_message_id\":\"policy-midstream\","
                                + "\"content\":\"POLICY_CANCEL_MIDSTREAM 推荐拍照手机\"}");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        Thread.sleep(1200);
        String runId = runIdOf(sessionId, token);
        assertThat(runId).isNotBlank();
        HttpResponse<String> cancel = call("POST", "/api/v1/agent/runs/" + runId + ":cancel",
                token, null);
        assertThat(cancel.statusCode()).isEqualTo(200);
        HttpResponse<String> streamed = pending.get(4, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMs).isLessThan(6000);
        assertThat(streamed.body()).contains("\"code\":\"canceled\"");
        JsonNode detail = json(call("GET", "/api/v1/agent/sessions/" + sessionId, token, null));
        assertThat(detail.get("messages").get(0).get("runs").get(0).get("status").asText())
                .isEqualTo("canceled");
    }

    @Test
    void clientDisconnectStopsRun() throws Exception {
        String token = registerUser();
        String sessionId = createSession(token);
        java.net.Socket socket = new java.net.Socket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        String body = "{\"client_message_id\":\"policy-disconnect\","
                + "\"content\":\"POLICY_CANCEL_MIDSTREAM 推荐拍照手机\"}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "POST " + streamPath(sessionId) + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Authorization: Bearer " + token + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bodyBytes);
        socket.getOutputStream().flush();
        byte[] buffer = new byte[4096];
        int read = socket.getInputStream().read(buffer);
        assertThat(read).isPositive();
        socket.close();

        String status = "";
        for (int i = 0; i < 40; i++) {
            Thread.sleep(150);
            JsonNode detail = json(call("GET", "/api/v1/agent/sessions/" + sessionId,
                    token, null));
            JsonNode runs = detail.get("messages").get(0).get("runs");
            if (!runs.isEmpty()) {
                status = runs.get(0).get("status").asText();
                if ("canceled".equals(status)) {
                    break;
                }
            }
        }
        assertThat(status).isEqualTo("canceled");
    }

    private String registerUser() throws Exception {
        String username = "policy_user_" + System.currentTimeMillis();
        HttpResponse<String> response = call("POST", "/api/v1/auth/register", null,
                "{\"username\":\"" + username + "\",\"password\":\"password123\","
                        + "\"display_name\":\"策略测试\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response).get("token").asText();
    }

    private String createSession(String token) throws Exception {
        return json(call("POST", "/api/v1/agent/sessions", token, "{}"))
                .get("session_id").asText();
    }

    private String streamPath(String sessionId) {
        return "/api/v1/agent/sessions/" + sessionId + "/messages:stream";
    }

    private String runIdOf(String sessionId, String token) throws Exception {
        JsonNode detail = json(call("GET", "/api/v1/agent/sessions/" + sessionId, token, null));
        JsonNode runs = detail.get("messages").get(0).get("runs");
        return runs.isEmpty() ? "" : runs.get(0).get("run_id").asText();
    }

    private int cartQuantity(String token, String productId) throws Exception {
        JsonNode cart = json(call("GET", "/api/v1/cart", token, null));
        for (JsonNode item : cart.get("items")) {
            if (productId.equals(item.get("productId").asText())) {
                return item.get("quantity").asInt();
            }
        }
        return 0;
    }

    private int orderCount(String token) throws Exception {
        return json(call("GET", "/api/v1/orders", token, null)).get("total").asInt();
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

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/chat/completions", AgentPolicyIT::handleChat);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void handleChat(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String content;
        boolean finalAnswer = false;
        if (body.contains("POLICY_CANCEL_MIDSTREAM")) {
            sendPartialStream(exchange);
            return;
        }
        if (body.contains("POLICY_CANCEL")) {
            sleepQuietly(8000);
            content = "超时未取消";
            finalAnswer = true;
        } else if (body.contains("POLICY_GUESS_ADD")) {
            if (body.contains("observation: ")) {
                content = "不能直接加购";
                finalAnswer = true;
            } else {
                content = toolCall("add_cart_item", Map.of("product_id", "p_001", "quantity", 1));
            }
        } else if (body.contains("POLICY_ALLOW_ADD")) {
            if (body.contains("observation: ")) {
                content = "已加入购物车";
                finalAnswer = true;
            } else {
                content = toolCall("add_cart_item", Map.of("product_id", "p_001", "quantity", 1));
            }
        } else if (body.contains("POLICY_DENY_CHECKOUT")) {
            if (body.contains("observation: ")) {
                content = "不能结算";
                finalAnswer = true;
            } else {
                content = toolCall("checkout", Map.of());
            }
        } else {
            content = "好的";
            finalAnswer = true;
        }
        if (body.contains("\"stream\":true")) {
            sendStream(exchange, finalAnswer ? "<final>" + content + "</final>" : content);
        } else {
            sendJson(exchange, content);
        }
    }

    private static void sendPartialStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try {
            String first = "data: " + MAPPER.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("delta", Map.of("content", "<final>部分"))))) + "\n\n";
            exchange.getResponseBody().write(first.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            sleepQuietly(8000);
            String rest = "data: " + MAPPER.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("delta", Map.of("content", "回答</final>"))))) + "\n\n"
                    + "data: [DONE]\n\n";
            exchange.getResponseBody().write(rest.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        } catch (IOException ignored) {
            // client canceled/closed
        } finally {
            exchange.close();
        }
    }

    private static void sendJson(HttpExchange exchange, String content) throws IOException {
        String json = MAPPER.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", content)))));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendStream(HttpExchange exchange, String content) throws IOException {        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try {
            int chunkSize = 3;
            for (int start = 0; start < content.length(); start += chunkSize) {
                int end = Math.min(start + chunkSize, content.length());
                String chunk = content.substring(start, end);
                String frame = "data: " + MAPPER.writeValueAsString(Map.of(
                        "choices", List.of(Map.of("delta", Map.of("content", chunk))))) + "\n\n";
                exchange.getResponseBody().write(frame.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            }
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        } catch (IOException ignored) {
            // client canceled and closed the stream
        } finally {
            exchange.close();
        }
    }

    private static String toolCall(String tool, Map<String, Object> args) {
        try {
            return MAPPER.writeValueAsString(Map.of("type", "tool_call", "tool", tool,
                    "arguments", args));
        } catch (RuntimeException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String finalText(String text) {
        try {
            return MAPPER.writeValueAsString(Map.of("type", "final", "text", text));
        } catch (RuntimeException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
