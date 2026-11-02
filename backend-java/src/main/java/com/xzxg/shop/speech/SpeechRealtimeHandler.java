package com.xzxg.shop.speech;

import com.xzxg.shop.config.ShopProperties;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Realtime ASR proxy matching the Go protocol: the client sends control JSON
 * (start/end/cancel) and audio frames; the backend proxies Xunfei and emits
 * ready/partial/stable/final/error/closed events.
 */
@Component
public class SpeechRealtimeHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SpeechRealtimeHandler.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final ShopProperties.Realtime config;
    private final ObjectMapper objectMapper;
    private final Map<String, Proxy> proxies = new ConcurrentHashMap<>();

    public SpeechRealtimeHandler(ShopProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getSpeech().getRealtime();
        this.objectMapper = objectMapper;
    }

    private record Proxy(WebSocketSession client, WebSocket upstream) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession client) throws Exception {
        try {
            WebSocket upstream = HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(signedUri(client.getId()), new UpstreamListener(client))
                    .get(10, TimeUnit.SECONDS);
            proxies.put(client.getId(), new Proxy(client, upstream));
            send(client, Map.of("type", "ready"));
        } catch (Exception ex) {
            log.warn("xunfei speech websocket dial failed: {}", ex.getMessage());
            send(client, Map.of("type", "error", "code", "speech_network_error",
                    "message", "语音识别服务暂时不可用"));
            client.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession client, TextMessage message) throws Exception {
        Proxy proxy = proxies.get(client.getId());
        if (proxy == null) {
            return;
        }
        String payload = message.getPayload();
        try {
            JsonNode node = objectMapper.readTree(payload);
            String type = node.path("type").asString("");
            if (!type.isEmpty()) {
                switch (type) {
                    case "start" -> {
                        return;
                    }
                    case "end" -> {
                        proxy.upstream().sendText("{\"data\":{\"status\":2}}", true);
                        return;
                    }
                    case "cancel" -> {
                        closeBoth(client);
                        return;
                    }
                    default -> {
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // not a control frame
        }
        proxy.upstream().sendText(payload, true);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession client, BinaryMessage message) throws Exception {
        Proxy proxy = proxies.get(client.getId());
        if (proxy != null) {
            ByteBuffer buffer = ByteBuffer.wrap(message.getPayload().array());
            proxy.upstream().sendBinary(buffer, true);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession client, CloseStatus status) {
        closeBoth(client);
    }

    private void closeBoth(WebSocketSession client) {
        Proxy proxy = proxies.remove(client.getId());
        if (proxy != null) {
            try {
                proxy.upstream().sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private void send(WebSocketSession session, Object payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (Exception ex) {
            log.debug("speech client send failed: {}", ex.getMessage());
        }
    }

    private URI signedUri(String uuid) throws Exception {
        URI base = URI.create(config.getBaseUrl());
        String basePath = base.getPath() == null ? "" : base.getPath();
        String path = trimRight(basePath) + "/" + trimLeft(config.getPath());
        Map<String, String> params = new TreeMap<>();
        params.put("appId", config.getAppId());
        params.put("accessKeyId", config.getApiKey());
        params.put("utc", UTC.format(ZonedDateTime.now()));
        params.put("audio_encode", config.getAudioEncode());
        params.put("lang", config.getLang());
        params.put("samplerate", config.getSampleRate());
        if (uuid != null && !uuid.isBlank()) {
            params.put("uuid", uuid);
        }
        String canonical = params.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(config.getApiSecret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA1"));
        String signature = Base64.getEncoder().encodeToString(
                mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        params.put("signature", signature);
        String query = params.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        return URI.create(base.getScheme() + "://" + base.getAuthority() + path + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String trimRight(String value) {
        return value.replaceAll("/+$", "");
    }

    private static String trimLeft(String value) {
        return value.replaceAll("^/+", "");
    }

    private final class UpstreamListener implements WebSocket.Listener {
        private final WebSocketSession client;
        private final StringBuilder buffer = new StringBuilder();
        private int seq;

        private UpstreamListener(WebSocketSession client) {
            this.client = client;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                handleFrame(frame);
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        private void handleFrame(String frame) {
            try {
                JsonNode node = objectMapper.readTree(frame);
                if (node.path("code").asInt(0) != 0) {
                    return;
                }
                String msgType = node.path("msg_type").asString("");
                if (!msgType.isEmpty() && !"result".equals(msgType)) {
                    return;
                }
                String resType = node.path("res_type").asString("");
                if (!resType.isEmpty() && !"asr".equals(resType)) {
                    return;
                }
                JsonNode data = node.path("data");
                JsonNode st = data.path("cn").path("st");
                StringBuilder text = new StringBuilder();
                for (JsonNode rt : st.path("rt")) {
                    for (JsonNode ws : rt.path("ws")) {
                        for (JsonNode cw : ws.path("cw")) {
                            text.append(cw.path("w").asString(""));
                        }
                    }
                }
                String value = text.toString();
                boolean finalResult = data.path("ls").asBoolean(false);
                if (!value.isEmpty()) {
                    String eventType = finalResult ? "final"
                            : ("0".equals(st.path("type").asString("")) ? "stable" : "partial");
                    seq++;
                    send(client, Map.of("type", eventType, "text", value, "seq", seq));
                }
                if (finalResult) {
                    send(client, Map.of("type", "closed"));
                    try {
                        client.close(CloseStatus.NORMAL);
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
            } catch (Exception ex) {
                log.debug("xunfei speech frame parse failed: {}", ex.getMessage());
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            send(client, Map.of("type", "closed"));
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("xunfei speech websocket error: {}", error.getMessage());
            send(client, Map.of("type", "error", "code", "speech_network_error",
                    "message", "语音识别服务暂时不可用"));
        }
    }
}
