package com.xzxg.shop.agent;

import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuideSuggestionController {

    private final GuideSuggestionService suggestions;

    public GuideSuggestionController(GuideSuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    public record GuideSuggestionRequest(String page, Map<String, Object> context, Integer limit) {
    }

    @PostMapping("/api/v1/agent/guide-suggestions")
    public Map<String, List<GuideSuggestionService.Item>> suggestions(
            @RequestBody(required = false) GuideSuggestionRequest body,
            HttpServletRequest request) {
        if (RequestContext.account(request) == null) {
            throw ApiException.unauthorized();
        }
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
        }
        int limit = body.limit() == null ? 3 : body.limit();
        List<GuideSuggestionService.Item> items;
        try {
            items = suggestions.build(body.page(), body.context(), limit);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_page", "向导建议请求参数不合法");
        }
        List<GuideSuggestionService.Item> refined = suggestions.refine(body.page(),
                body.context(), limit, items);
        return Map.of("items", refined);
    }
}
