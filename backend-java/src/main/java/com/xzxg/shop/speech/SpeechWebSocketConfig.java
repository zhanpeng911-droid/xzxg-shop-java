package com.xzxg.shop.speech;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSocket
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SpeechWebSocketConfig implements WebSocketConfigurer {

    private final SpeechRealtimeHandler handler;
    private final SpeechService speech;
    private final ObjectMapper objectMapper;

    public SpeechWebSocketConfig(SpeechRealtimeHandler handler, SpeechService speech,
                                 ObjectMapper objectMapper) {
        this.handler = handler;
        this.speech = speech;
        this.objectMapper = objectMapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/speech/realtime")
                .addInterceptors(new RealtimeHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    private final class RealtimeHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes)
                throws Exception {
            if (!speech.realtimeEnabled()) {
                writeError(response, HttpStatus.NOT_IMPLEMENTED,
                        "speech_not_enabled", "当前后端未配置讯飞语音识别");
                return false;
            }
            String sampleRate = queryParam(request.getURI().getQuery(), "sample_rate");
            if (sampleRate != null && !sampleRate.isBlank()
                    && !sampleRate.equals(speech.realtimeSampleRate())) {
                writeError(response, HttpStatus.BAD_REQUEST, "bad_sample_rate",
                        "当前仅支持 16000Hz PCM 音频");
                return false;
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // no-op
        }
    }

    private void writeError(ServerHttpResponse response, HttpStatus status, String code,
                            String message) throws Exception {
        response.setStatusCode(status);
        response.getHeaders().set("Content-Type", "application/json; charset=utf-8");
        response.getBody().write(objectMapper.writeValueAsBytes(
                Map.of("code", code, "message", message)));
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
