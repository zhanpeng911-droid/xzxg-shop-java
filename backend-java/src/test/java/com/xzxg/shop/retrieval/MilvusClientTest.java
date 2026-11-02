package com.xzxg.shop.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xzxg.shop.config.ShopProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MilvusClientTest {

    private static HttpServer server;
    private static final List<String> paths = new CopyOnWriteArrayList<>();
    private static final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", MilvusClientTest::handle);
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void ensureUpsertLoadAndSearchUseExpectedRestContract() throws Exception {
        paths.clear();
        bodies.clear();
        ShopProperties properties = new ShopProperties();
        properties.getRetrieval().getVector().setEnabled(true);
        properties.getRetrieval().getVector().setMilvusAddress(
                "http://127.0.0.1:" + server.getAddress().getPort());
        properties.getRetrieval().getVector().setMinScore(0);
        MilvusClient client = new MilvusClient(properties, JsonMapper.builder().build());

        client.ensureCollection("product_text_vectors", "product_id", 8);
        client.upsert("product_text_vectors", List.of(
                Map.of("product_id", "p_001", "embedding", new float[]{0.1f, 0.2f})));
        client.loadCollection("product_text_vectors");
        List<MilvusClient.SearchHit> hits = client.search("product_text_vectors",
                new float[]{0.1f, 0.2f}, "product_id", List.of("product_id"), 5);

        assertThat(paths).containsExactly(
                "/v2/vectordb/collections/create",
                "/v2/vectordb/entities/upsert",
                "/v2/vectordb/collections/load",
                "/v2/vectordb/entities/search");
        assertThat(bodies.get(0)).contains("\"collectionName\":\"product_text_vectors\"")
                .contains("\"dimension\":8")
                .contains("\"primaryFieldName\":\"product_id\"")
                .contains("\"vectorFieldName\":\"embedding\"");
        assertThat(bodies.get(1)).contains("\"product_id\":\"p_001\"");
        assertThat(bodies.get(3)).contains("\"annsField\":\"embedding\"");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo("p_001");
        assertThat(hits.get(0).score()).isEqualTo(0.9);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String body;
        if (exchange.getRequestURI().getPath().endsWith("/entities/search")) {
            body = "{\"code\":0,\"data\":[{\"product_id\":\"p_001\",\"distance\":0.9}]}";
        } else if (exchange.getRequestURI().getPath().endsWith("/get_stats")) {
            body = "{\"code\":0,\"data\":{\"rowCount\":1}}";
        } else {
            body = "{\"code\":0}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
