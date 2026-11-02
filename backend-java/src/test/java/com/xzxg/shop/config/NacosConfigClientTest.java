package com.xzxg.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xzxg.shop.domain.AppConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NacosConfigClientTest {

    private static HttpServer server;
    private static final List<String> publishedBodies = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/nacos/v1/cs/configs", NacosConfigClientTest::handle);
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void readsGoDocumentsAndLegacyMapAndPublishesConfigsDocument() throws Exception {
        ShopProperties properties = new ShopProperties();
        properties.getNacos().setEnabled(true);
        properties.getNacos().setServerAddr("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getNacos().setDataId("legacy-config.json");
        NacosConfigClient client = new NacosConfigClient(properties,
                JsonMapper.builder().build());

        List<AppConfig> loaded = client.load();
        Map<String, String> values = loaded.stream().collect(
                java.util.stream.Collectors.toMap(AppConfig::configKey, AppConfig::configValue));
        assertThat(values).containsEntry("http.cors.allowed_origins", "*")
                .containsEntry("ai.qwen.api_key", "secret-value")
                .containsEntry("retrieval.keyword.top_n", "123");
        assertThat(loaded.stream().filter(item -> item.configKey().equals("ai.qwen.api_key"))
                .findFirst().orElseThrow().isSecret()).isTrue();

        publishedBodies.clear();
        client.publish(List.of(
                new AppConfig("http.cors.allowed_origins", "*", "string", "", "app", false,
                        "2026-11-02T10:00:00+08:00"),
                new AppConfig("vector.enabled", "true", "bool", "", "infra", false,
                        "2026-11-02T10:00:00+08:00")));
        assertThat(publishedBodies).hasSize(2);
        assertThat(publishedBodies).allSatisfy(body -> assertThat(body).contains("configs"));
        assertThat(publishedBodies.stream().anyMatch(body ->
                body.contains("http.cors.allowed_origins"))).isTrue();
        assertThat(publishedBodies.stream().anyMatch(body ->
                body.contains("vector.enabled"))).isTrue();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            publishedBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, 200, "true");
            return;
        }
        String dataId = queryParameter(query, "dataId");
        if (dataId == null) {
            respond(exchange, 404, "");
            return;
        }
        switch (dataId) {
            case "xzxg-shop-app-config.json" -> respond(exchange, 200, """
                    {"configs":[{"config_key":"http.cors.allowed_origins",
                    "config_value":"*","value_type":"string","description":"",
                    "domain":"app","is_secret":false,"updated_at":"2026-11-02T10:00:00+08:00"}]}
                    """);
            case "xzxg-shop-infra-config.json" -> respond(exchange, 200, """
                    {"configs":[{"config_key":"ai.qwen.api_key",
                    "config_value":"secret-value","value_type":"string","description":"",
                    "domain":"infra","is_secret":true,"updated_at":"2026-11-02T10:00:00+08:00"}]}
                    """);
            case "xzxg-shop-rag-config.json" -> respond(exchange, 200,
                    "{\"retrieval.keyword.top_n\":\"123\"}");
            default -> respond(exchange, 404, "config data not exist");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String queryParameter(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
