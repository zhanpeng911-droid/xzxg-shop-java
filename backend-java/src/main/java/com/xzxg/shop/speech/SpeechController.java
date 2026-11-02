package com.xzxg.shop.speech;

import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SpeechController {

    private final SpeechService speech;

    public SpeechController(SpeechService speech) {
        this.speech = speech;
    }

    public record TtsRequest(String text, String voice) {
    }

    @GetMapping("/api/v1/speech/tts/config")
    public Map<String, Object> ttsConfig(HttpServletRequest request) {
        requireUser(request);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", speech.ttsEnabled());
        response.put("provider", speech.provider());
        response.put("voice", speech.defaultVoice());
        response.put("max_text_chars", speech.maxTextRunes());
        return response;
    }

    @PostMapping("/api/v1/speech/tts")
    public ResponseEntity<byte[]> tts(@RequestBody(required = false) TtsRequest body,
                                      HttpServletRequest request) {
        requireUser(request);
        if (!speech.ttsEnabled()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "tts_not_enabled",
                    "当前后端未配置讯飞语音合成");
        }
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
        }
        String text = body.text() == null ? "" : body.text().trim();
        if (text.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_text", "合成文本不能为空");
        }
        if (speech.maxTextRunes() > 0
                && text.codePointCount(0, text.length()) > speech.maxTextRunes()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "text_too_long", "合成文本过长");
        }
        byte[] audio;
        try {
            audio = speech.synthesize(text, body.voice());
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "tts_failed", "语音合成服务暂时不可用");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(speech.contentType()))
                .body(audio);
    }

    private static void requireUser(HttpServletRequest request) {
        if (RequestContext.account(request) == null) {
            throw ApiException.unauthorized();
        }
    }
}
