package com.xzxg.shop.config;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Nacos v1 config API client. The data id stores a JSON object of config key/value
 * pairs. Disabled unless {@code shop.nacos.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "shop.nacos", name = "enabled", havingValue = "true")
public class NacosConfigClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ShopProperties.Nacos config;
    private final ObjectMapper objectMapper;
    private volatile String accessToken = "";

    public NacosConfigClient(ShopProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getNacos();
        this.objectMapper = objectMapper;
    }

    public Optional<Map<String, String>> load() throws Exception {
        authenticate();
        String url = base() + "/nacos/v1/cs/configs?dataId=" + encode(config.getDataId())
                + "&group=" + encode(config.getGroup())
                + (config.getNamespace().isBlank() ? "" : "&tenant=" + encode(config.getNamespace()))
                + (accessToken.isBlank() ? "" : "&accessToken=" + encode(accessToken));
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("nacos load status " + response.statusCode());
        }
        if (response.body().isBlank()) {
            return Optional.empty();
        }
        Map<String, String> values = objectMapper.readValue(response.body(),
                new TypeReference<Map<String, String>>() {
                });
        return Optional.of(values);
    }

    public void publish(Map<String, String> values) throws Exception {
        authenticate();
        String content = objectMapper.writeValueAsString(values);
        StringBuilder form = new StringBuilder();
        form.append("dataId=").append(encode(config.getDataId()));
        form.append("&group=").append(encode(config.getGroup()));
        if (!config.getNamespace().isBlank()) {
            form.append("&tenant=").append(encode(config.getNamespace()));
        }
        form.append("&content=").append(encode(content));
        form.append("&type=json");
        if (!accessToken.isBlank()) {
            form.append("&accessToken=").append(encode(accessToken));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/nacos/v1/cs/configs"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || !"true".equalsIgnoreCase(response.body().trim())) {
            throw new IllegalStateException("nacos publish failed: " + response.statusCode()
                    + " " + response.body());
        }
    }

    private void authenticate() throws Exception {
        if (config.getUsername().isBlank() || config.getPassword().isBlank()
                || !accessToken.isEmpty()) {
            return;
        }
        String form = "username=" + encode(config.getUsername())
                + "&password=" + encode(config.getPassword());
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/nacos/v1/auth/login"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            accessToken = objectMapper.readTree(response.body()).path("accessToken")
                    .asString("");
        }
    }

    private String base() {
        return config.getServerAddr().replaceAll("/+$", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
