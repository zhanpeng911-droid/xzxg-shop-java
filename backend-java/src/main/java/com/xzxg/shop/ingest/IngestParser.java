package com.xzxg.shop.ingest;

import com.xzxg.shop.domain.UnstructuredIngestRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Port of {@code ingest.Parse}: fetch/clean/normalize unstructured content.
 */
@Component
public class IngestParser {

    private static final int MAX_FETCH_BYTES = 2 << 20;
    private static final int MAX_TEXT_RUNES = 120_000;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final ObjectMapper objectMapper;

    public IngestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ParsedDocument(String title, String docType, String content, String sourceUrl,
                                 Map<String, Object> metadata, int textRunes,
                                 String sourceType) {
    }

    public ParsedDocument parse(UnstructuredIngestRequest request) {
        String sourceType = normalizeSourceType(request.sourceType());
        String raw = firstNonEmpty(request.content(), request.html(), request.jsonText());
        String sourceUrl = trim(request.sourceUrl());
        if (raw.isEmpty() && !sourceUrl.isEmpty()) {
            raw = fetchUrl(sourceUrl);
            if ("text".equals(sourceType)) {
                sourceType = inferSourceType(sourceUrl, raw);
            }
        }
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("empty unstructured content");
        }
        String content = switch (sourceType) {
            case "html" -> htmlToText(raw);
            case "json" -> jsonToText(raw);
            default -> plainText(raw);
        };
        content = truncateRunes(content, MAX_TEXT_RUNES);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("empty parsed text");
        }
        String title = trim(request.title());
        if (title.isEmpty()) {
            title = inferTitle(content, sourceUrl);
        }
        return new ParsedDocument(title, docTypeForSource(sourceType), content, sourceUrl,
                request.metadata(), content.codePointCount(0, content.length()), sourceType);
    }

    static String fetchUrl(String sourceUrl) {
        if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) {
            throw new IllegalArgumentException("source_url must be http or https");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "xzxg-shop-rag-ingest/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("fetch source_url failed: status "
                        + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > MAX_FETCH_BYTES) {
                throw new IllegalArgumentException("fetched document too large");
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("fetch source_url failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("fetch source_url interrupted", ex);
        }
    }

    static String htmlToText(String input) {
        String text = input.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</(script|style|noscript)>", "\n");
        text = text.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n");
        text = text.replaceAll("(?s)<[^>]+>", "\n");
        return plainText(unescapeHtml(text));
    }

    private String jsonToText(String input) {
        try {
            JsonNode node = objectMapper.readTree(input);
            List<String> lines = new ArrayList<>();
            flattenJson("", node, lines);
            return plainText(String.join("\n", lines));
        } catch (RuntimeException ex) {
            return plainText(input);
        }
    }

    private static void flattenJson(String prefix, JsonNode node, List<String> lines) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.forEachEntry((key, child) -> {
                String next = prefix.isEmpty() ? key : prefix + "." + key;
                flattenJson(next, child, lines);
            });
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenJson(prefix + "[" + i + "]", node.get(i), lines);
            }
            return;
        }
        if (node.isString()) {
            String value = node.asString();
            if (!value.trim().isEmpty()) {
                lines.add(prefix + ": " + value);
            }
            return;
        }
        lines.add(prefix + ": " + node.asString());
    }

    static String plainText(String input) {
        String text = input.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = text.split("\n", -1);
        List<String> cleaned = new ArrayList<>(lines.length);
        for (String line : lines) {
            String normalized = line.trim().replaceAll("[ \\t\\f\\v]+", " ");
            if (!normalized.isEmpty()) {
                cleaned.add(normalized);
            }
        }
        text = String.join("\n", cleaned);
        text = text.replaceAll("\n{3,}", "\n\n");
        return text.trim();
    }

    static String inferTitle(String content, String sourceUrl) {
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return truncateRunes(trimmed, 80);
            }
        }
        return sourceUrl.isEmpty() ? "非结构化资料" : sourceUrl;
    }

    private static String normalizeSourceType(String sourceType) {
        String normalized = trim(sourceType).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "html", "web", "page" -> "html";
            case "json", "jsonl" -> "json";
            default -> "text";
        };
    }

    private static String inferSourceType(String sourceUrl, String body) {
        String lowerUrl = sourceUrl.toLowerCase(Locale.ROOT);
        if (lowerUrl.endsWith(".json") || lowerUrl.endsWith(".jsonl")) {
            return "json";
        }
        String lowerBody = body.toLowerCase(Locale.ROOT);
        if (lowerBody.contains("<html") || lowerBody.contains("<body")) {
            return "html";
        }
        return "text";
    }

    private static String docTypeForSource(String sourceType) {
        return switch (sourceType) {
            case "html" -> "web_article";
            case "json" -> "structured_note";
            default -> "unstructured_note";
        };
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String unescapeHtml(String input) {
        return input.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    private static String truncateRunes(String value, int max) {
        int[] codePoints = value.codePoints().toArray();
        if (codePoints.length <= max) {
            return value;
        }
        return new String(codePoints, 0, max);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
