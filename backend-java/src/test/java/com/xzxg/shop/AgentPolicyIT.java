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
    private static final List<String> requestModels = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<String> requestSystemModels =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void agentProperties(DynamicPropertyRegistry registry) {
        registry.add("shop.agent.base-url",
                () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.xzxg.shop.config.ConfigService configService;

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

    @Test
    void memoryRetrievalResolvesPreviousProductReference() throws Exception {
        String token = registerUser();
        String sessionId = createSession(token);
        HttpResponse<String> seed = call("POST", streamPath(sessionId), token,
                "{\"client_message_id\":\"memory-seed\",\"content\":\"MEMORY_SEED 推荐拍照手机 p_001\"}");
        assertThat(seed.statusCode()).isEqualTo(200);
        assertThat(seed.body()).contains("\"type\":\"product_refs\"");
        assertThat(cartQuantity(token, "p_001")).isZero();

        HttpResponse<String> add = call("POST", streamPath(sessionId), token,
                "{\"client_message_id\":\"memory-add\","
                        + "\"content\":\"MEMORY_ADD 把刚才那个加入购物车\"}");
        assertThat(add.statusCode()).isEqualTo(200);
        assertThat(add.body()).contains("\"type\":\"cart_state\"");
        assertThat(cartQuantity(token, "p_001")).isEqualTo(1);
    }

    @Test
    void publishedRoutePromptAndRoleModelTakeEffect() throws Exception {
        String adminToken = login("admin", "admin123456");
        HttpResponse<String> draft = call("PATCH", "/api/v1/admin/prompts/agent.prompt.route",
                adminToken, "{\"title\":\"route\",\"content\":\"CUSTOM_ROUTE_PROMPT "
                        + "你是电商前置意图路由器，只输出 JSON。\",\"description\":\"test\"}");
        assertThat(draft.statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/v1/admin/prompts/agent.prompt.route/publish",
                adminToken, null).statusCode()).isEqualTo(200);
        configService.update("ai.model.planner_route", "custom-route-model",
                "string", "test override", false);
        requestModels.clear();

        String token = registerUser();
        String sessionId = createSession(token);
        HttpResponse<String> stream = call("POST", streamPath(sessionId), token,
                "{\"client_message_id\":\"custom-route\","
                        + "\"content\":\"CUSTOM_ROUTE_TEST 推荐拍照手机\"}");
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.body()).contains("用户想买手机，关注拍照和预算。");
        assertThat(stream.body()).contains("帮我对比这几款");
        assertThat(requestModels).contains("custom-route-model");
        assertThat(requestSystemModels).contains("route|custom-route-model");
        assertThat(requestSystemModels).contains("non_guide_intent|stub-model");
        assertThat(requestSystemModels).contains("need_summary|stub-model");
        assertThat(requestSystemModels).contains("react|stub-model");

        String runId = firstRunId(stream.body());
        JsonNode trace = json(call("GET", "/api/v1/agent/runs/" + runId + "/trace",
                token, null));
        JsonNode planner = null;
        for (JsonNode item : trace.get("items")) {
            if ("planner".equals(item.path("stage").asString())) {
                planner = item;
            }
        }
        assertThat(planner).isNotNull();
        JsonNode metadata = objectMapper.readTree(
                planner.path("metadata_json").asString());
        assertThat(metadata.path("route").asString()).isEqualTo("non_guide");

        JsonNode detail = json(call("GET", "/api/v1/agent/sessions/" + sessionId, token, null));
        assertThat(detail.path("session").path("summary").asString())
                .isEqualTo("用户想买拍照手机");
    }

    private String login(String username, String password) throws Exception {
        HttpResponse<String> response = call("POST", "/api/v1/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).get("token").asText();
    }

    private static String firstRunId(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"run_id\":\"(run_[0-9]+)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : "";
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
        String system = systemPrompt(body);
        recordModel(body, system);
        String content;
        boolean finalAnswer = false;
        boolean stream = body.contains("\"stream\":true");

        if (system.contains("前置意图路由器")) {
            boolean nonGuide = body.contains("POLICY_GUESS_ADD") || body.contains("POLICY_ALLOW_ADD")
                    || body.contains("MEMORY_ADD")
                    || (system.contains("CUSTOM_ROUTE_PROMPT")
                    && body.contains("CUSTOM_ROUTE_TEST"));
            content = "{\"reasoning\":\"stub route\",\"route\":\""
                    + (nonGuide ? "non_guide" : "guide") + "\"}";
            sendJson(exchange, content);
            return;
        }
        if (system.contains("导购意图细分类器")) {
            sendJson(exchange, "{\"reasoning\":\"stub\",\"is_guide\":true,"
                    + "\"intent\":\"category_shop_no_brand\",\"level\":\"P4\","
                    + "\"secondary_level\":\"P4B\"}");
            return;
        }
        if (system.contains("非导购服务意图分类器")) {
            sendJson(exchange, "{\"reasoning\":\"stub\",\"intent\":\"cart_service\"}");
            return;
        }
        if (system.contains("需求理解摘要器")) {
            sendJson(exchange, "用户想买手机，关注拍照和预算。");
            return;
        }
        if (system.contains("短期会话记忆检索器")) {
            if (body.contains("刚才") || body.contains("这个")) {
                sendJson(exchange, "{\"has_relevant_memory\":true,"
                        + "\"memory_summary\":\"上一轮推荐了 p_001\","
                        + "\"referenced_product_ids\":[\"p_001\"],\"used_record_ids\":[]}");
            } else {
                sendJson(exchange, "{\"has_relevant_memory\":false}");
            }
            return;
        }
        if (system.contains("追问生成器")) {
            sendJson(exchange, "[\"帮我对比这几款\",\"有没有更便宜的\"]");
            return;
        }
        if (system.contains("会话摘要器")) {
            sendJson(exchange, "{\"summary\":\"用户想买拍照手机\"}");
            return;
        }

        if (body.contains("MEMORY_ADD")) {
            finalAnswer = body.contains("observation: ");
            content = finalAnswer ? finalText("已加入购物车")
                    : toolCall("add_cart_item", Map.of("product_id", "p_001", "quantity", 1));
        } else if (body.contains("POLICY_CANCEL_MIDSTREAM")) {
            sendPartialStream(exchange);
            return;
        } else if (body.contains("POLICY_CANCEL")) {
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
        } else if (body.contains("MEMORY_SEED")) {
            content = "<item>p_001</item>推荐 p_001 拍照手机";
            finalAnswer = true;
        } else {
            content = "好的";
            finalAnswer = true;
        }
        if (stream) {
            sendStream(exchange, finalAnswer ? "<final>" + content + "</final>" : content);
        } else {
            sendJson(exchange, content);
        }
    }

    private static void recordModel(String body, String system) {
        try {
            String model = MAPPER.readTree(body).path("model").asString("");
            requestModels.add(model);
            requestSystemModels.add(tagOf(system) + "|" + model);
        } catch (RuntimeException ignored) {
            // no model field
        }
    }

    private static String tagOf(String system) {
        if (system.contains("前置意图路由器")) {
            return "route";
        }
        if (system.contains("导购意图细分类器")) {
            return "guide_intent";
        }
        if (system.contains("非导购服务意图分类器")) {
            return "non_guide_intent";
        }
        if (system.contains("需求理解摘要器")) {
            return "need_summary";
        }
        if (system.contains("短期会话记忆检索器")) {
            return "memory_retrieval";
        }
        if (system.contains("会话摘要器")) {
            return "memory_summary";
        }
        if (system.contains("追问生成器")) {
            return "followups";
        }
        return "react";
    }

    private static String systemPrompt(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode messages = root.path("messages");
            for (JsonNode message : messages) {
                if ("system".equals(message.path("role").asString())) {
                    return message.path("content").asString("");
                }
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "";
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
