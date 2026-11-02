package com.xzxg.shop.speech;

import com.xzxg.shop.config.ShopProperties;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** TTS adapters for Xunfei and Doubao, matching the Go signing/payload rules. */
@Service
public class SpeechService {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ShopProperties.Speech speech;
    private final ObjectMapper objectMapper;

    public SpeechService(ShopProperties properties, ObjectMapper objectMapper) {
        this.speech = properties.getSpeech();
        this.objectMapper = objectMapper;
    }

    public boolean realtimeEnabled() {
        ShopProperties.Realtime config = speech.getRealtime();
        return !config.getAppId().isBlank() && !config.getApiKey().isBlank()
                && !config.getApiSecret().isBlank();
    }

    public String realtimeSampleRate() {
        return speech.getRealtime().getSampleRate();
    }

    public boolean ttsEnabled() {        return "doubao".equals(speech.getTts().getProvider())
                ? doubaoEnabled() : xunfeiEnabled();
    }

    public String provider() {
        return speech.getTts().getProvider();
    }

    public String defaultVoice() {
        return "doubao".equals(provider())
                ? speech.getTts().getDoubao().getVoice()
                : speech.getTts().getXunfei().getVoice();
    }

    public int maxTextRunes() {
        return "doubao".equals(provider())
                ? speech.getTts().getDoubao().getMaxRunes()
                : speech.getTts().getXunfei().getMaxRunes();
    }

    public String contentType() {
        String encoding = "doubao".equals(provider())
                ? speech.getTts().getDoubao().getEncoding()
                : speech.getTts().getXunfei().getAudioEncoding();
        return switch (encoding == null ? "" : encoding.toLowerCase(Locale.ROOT)) {
            case "mp3", "lame" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg", "opus" -> "audio/ogg";
            default -> "application/octet-stream";
        };
    }

    public byte[] synthesize(String text, String requestedVoice) throws Exception {
        if ("doubao".equals(provider())) {
            return doubaoSynthesize(text, requestedVoice);
        }
        return xunfeiSynthesize(text, requestedVoice);
    }

    private boolean xunfeiEnabled() {
        ShopProperties.Xunfei config = speech.getTts().getXunfei();
        return config.isEnabled() && !config.getAppId().isBlank()
                && !config.getApiKey().isBlank() && !config.getApiSecret().isBlank()
                && !config.getBaseUrl().isBlank();
    }

    private boolean doubaoEnabled() {
        ShopProperties.Doubao config = speech.getTts().getDoubao();
        return config.isEnabled() && !config.getAppId().isBlank()
                && !config.getApiKey().isBlank() && !config.getBaseUrl().isBlank()
                && !config.getCluster().isBlank();
    }

    private byte[] xunfeiSynthesize(String text, String requestedVoice) throws Exception {
        ShopProperties.Xunfei config = speech.getTts().getXunfei();
        String voice = requestedVoice == null || requestedVoice.isBlank()
                ? config.getVoice() : requestedVoice;
        URI uri = signedXunfeiUri(config);
        Map<String, Object> payload = Map.of(
                "common", Map.of("app_id", config.getAppId()),
                "business", Map.of(
                        "aue", config.getAudioEncoding(),
                        "sfl", 1,
                        "vcn", voice,
                        "speed", config.getSpeed(),
                        "volume", config.getVolume(),
                        "pitch", config.getPitch(),
                        "tte", config.getTextEncoding()),
                "data", Map.of("status", 2,
                        "text", Base64.getEncoder().encodeToString(
                                text.getBytes(StandardCharsets.UTF_8))));
        CountDownLatch done = new CountDownLatch(1);
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        AtomicReference<String> error = new AtomicReference<>();
        WebSocket socket = HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data,
                                                     boolean last) {
                        buffer.append(data);
                        if (last) {
                            String frame = buffer.toString();
                            buffer.setLength(0);
                            try {
                                JsonNode node = objectMapper.readTree(frame);
                                int code = node.path("code").asInt(0);
                                if (code != 0) {
                                    error.set("xunfei tts code " + code + ": "
                                            + node.path("message").asString(""));
                                } else {
                                    String audioBase64 = node.path("data").path("audio").asString("");
                                    if (!audioBase64.isEmpty()) {
                                        audio.writeBytes(Base64.getDecoder().decode(audioBase64));
                                    }
                                    if (node.path("data").path("status").asInt(0) == 2) {
                                        done.countDown();
                                    }
                                }
                            } catch (Exception ex) {
                                error.set(ex.getMessage());
                            }
                            if (error.get() != null) {
                                done.countDown();
                            }
                        }
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }
                }).get(10, TimeUnit.SECONDS);
        try {
            socket.sendText(objectMapper.writeValueAsString(payload), true);
            int timeout = config.getTimeoutSeconds() <= 0 ? 20 : config.getTimeoutSeconds();
            if (!done.await(timeout, TimeUnit.SECONDS)) {
                throw new IllegalStateException("xunfei tts timeout");
            }
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
        return audio.toByteArray();
    }

    private byte[] doubaoSynthesize(String text, String requestedVoice) throws Exception {
        ShopProperties.Doubao config = speech.getTts().getDoubao();
        String voice = requestedVoice == null || requestedVoice.isBlank()
                ? config.getVoice() : requestedVoice;
        String reqId = java.util.UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("app", Map.of("appid", config.getAppId(), "token", config.getApiKey(),
                "cluster", config.getCluster()));
        payload.put("user", Map.of("uid", config.getUid()));
        payload.put("audio", Map.of(
                "voice_type", voice,
                "encoding", config.getEncoding(),
                "speed_ratio", config.getSpeedRatio(),
                "volume_ratio", config.getVolumeRatio(),
                "pitch_ratio", config.getPitchRatio()));
        payload.put("request", Map.of(
                "reqid", reqId,
                "text", text,
                "text_type", config.getTextType(),
                "operation", config.getOperation(),
                "with_frontend", config.getWithFrontend(),
                "frontend_type", config.getFrontendType()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.getBaseUrl()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer;" + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("doubao tts status " + response.statusCode());
        }
        JsonNode node = objectMapper.readTree(response.body());
        if (node.path("code").asInt(0) != 0 && node.has("code")) {
            throw new IllegalStateException("doubao tts code " + node.path("code").asInt()
                    + ": " + node.path("message").asString(""));
        }
        String data = node.path("data").asString("");
        if (data.isEmpty()) {
            throw new IllegalStateException("doubao tts empty data");
        }
        return Base64.getDecoder().decode(data);
    }

    static URI signedXunfeiUri(ShopProperties.Xunfei config) throws Exception {
        URI base = URI.create(config.getBaseUrl());
        String host = base.getHost();
        String date = HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
        String path = base.getPath() == null || base.getPath().isEmpty()
                ? "/v2/tts" : base.getPath();
        String signatureOrigin = "host: " + host + "\n" + "date: " + date + "\n"
                + "GET " + path + " HTTP/1.1";
        String signature = Base64.getEncoder().encodeToString(
                hmac("HmacSHA256", config.getApiSecret(), signatureOrigin));
        String authorizationOrigin = String.format(Locale.ROOT,
                "api_key=\"%s\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"%s\"",
                config.getApiKey(), signature);
        String authorization = Base64.getEncoder().encodeToString(
                authorizationOrigin.getBytes(StandardCharsets.UTF_8));
        String scheme = base.getScheme() == null ? "wss" : base.getScheme();
        int port = base.getPort();
        return URI.create(scheme + "://" + host + (port > 0 ? ":" + port : "") + path
                + "?authorization=" + encode(authorization)
                + "&date=" + encode(date)
                + "&host=" + encode(host));
    }

    static byte[] hmac(String algorithm, String secret, String data) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
