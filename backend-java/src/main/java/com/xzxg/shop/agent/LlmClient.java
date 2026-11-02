package com.xzxg.shop.agent;

import com.xzxg.shop.config.ShopProperties;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** OpenAI-compatible chat completions client. */
@Component
public class LlmClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ShopProperties.Agent config;
    private final ObjectMapper objectMapper;

    public LlmClient(ShopProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getAgent();
        this.objectMapper = objectMapper;
    }

    public record Message(String role, String content) {
    }

    public boolean enabled() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    public String smallModel() {
        return config.getSmallModel();
    }

    public String largeModel() {
        return config.getLargeModel();
    }

    public String complete(String model, List<Message> messages, double temperature) throws Exception {
        return complete(model, messages, temperature, () -> false);
    }

    public String complete(String model, List<Message> messages, double temperature,
                           BooleanSupplier canceled) throws Exception {
        if (!enabled()) {
            throw new IllegalStateException("llm disabled: missing api key");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model == null || model.isBlank() ? config.getSmallModel() : model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        payload.put("enable_thinking", config.isEnableThinking());
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> future = HTTP.sendAsync(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        HttpResponse<String> response;
        while (true) {
            if (canceled.getAsBoolean()) {
                future.cancel(true);
                throw new CancellationException("run canceled");
            }
            try {
                response = future.get(150, TimeUnit.MILLISECONDS);
                break;
            } catch (TimeoutException ex) {
                if (System.nanoTime() > deadline) {
                    future.cancel(true);
                    throw new IllegalStateException("llm timeout");
                }
            } catch (ExecutionException ex) {
                throw new IllegalStateException("llm call failed", ex.getCause());
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("llm status " + response.statusCode() + ": "
                    + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("llm response missing content");
        }
        return content.asString();
    }

    /**
     * Real incremental chat-completions stream. Emits content deltas as they arrive
     * and aborts the HTTP exchange when the run is canceled.
     */
    public String stream(String model, List<Message> messages, double temperature,
                         BooleanSupplier canceled, Consumer<String> onDelta) throws Exception {
        if (!enabled()) {
            throw new IllegalStateException("llm disabled: missing api key");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model == null || model.isBlank() ? config.getSmallModel() : model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        payload.put("enable_thinking", config.isEnableThinking());
        payload.put("stream", true);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<InputStream>> future = HTTP.sendAsync(request,
                HttpResponse.BodyHandlers.ofInputStream());
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        HttpResponse<InputStream> response;
        while (true) {
            if (canceled.getAsBoolean()) {
                future.cancel(true);
                throw new CancellationException("run canceled");
            }
            try {
                response = future.get(150, TimeUnit.MILLISECONDS);
                break;
            } catch (TimeoutException ex) {
                if (System.nanoTime() > deadline) {
                    future.cancel(true);
                    throw new IllegalStateException("llm stream timeout");
                }
            } catch (ExecutionException ex) {
                throw new IllegalStateException("llm stream failed", ex.getCause());
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream body = response.body()) {
                String error = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("llm stream status " + response.statusCode()
                        + ": " + error);
            }
        }
        StringBuilder collected = new StringBuilder();
        InputStream body = response.body();
        java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            while (!finished.get()) {
                if (canceled.getAsBoolean() || System.nanoTime() > deadline) {
                    try {
                        body.close();
                    } catch (Exception ignored) {
                        // closing the stream unblocks readLine promptly
                    }
                    return;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "llm-stream-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (canceled.getAsBoolean()) {
                    throw new CancellationException("run canceled");
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("llm stream timeout");
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(data);
                JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                if (delta.isString() && !delta.asString().isEmpty()) {
                    String text = delta.asString();
                    collected.append(text);
                    onDelta.accept(text);
                }
            }
        } catch (CancellationException ex) {
            throw ex;
        } catch (java.io.IOException | RuntimeException ex) {
            if (canceled.getAsBoolean()) {
                throw new CancellationException("run canceled");
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("llm stream timeout");
            }
            throw ex;
        } finally {
            finished.set(true);
            watchdog.interrupt();
        }
        if (canceled.getAsBoolean()) {
            throw new CancellationException("run canceled");
        }
        if (System.nanoTime() > deadline) {
            throw new IllegalStateException("llm stream timeout");
        }
        return collected.toString();
    }
}
