package com.xzxg.shop.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.image.ImageSearchService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class EvalController {

    private final EvalService evals;

    public EvalController(EvalService evals) {
        this.evals = evals;
    }

    public record IntentRequest(String query) {
    }

    public record RagRequest(String keyword, String query,
                             @JsonProperty("top_k") Integer topK) {
    }

    public record ProductRequest(String query, Integer limit) {
    }

    public record ImageRequest(@JsonProperty("file_id") String fileId,
                               @JsonProperty("object_key") String objectKey,
                               @JsonProperty("image_url") String imageUrl,
                               @JsonProperty("top_k") Integer topK) {
    }

    @PostMapping("/api/v1/eval/intent")
    public Map<String, String> intent(@RequestBody(required = false) IntentRequest body,
                                      HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        String query = body.query() == null ? "" : body.query().trim();
        if (query.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_query", "query 不能为空");
        }
        return evals.classify(query);
    }

    @PostMapping("/api/v1/eval/rag")
    public Map<String, Object> rag(@RequestBody(required = false) RagRequest body,
                                   HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        String keyword = body.keyword() == null ? "" : body.keyword().trim();
        if (keyword.isEmpty()) {
            keyword = body.query() == null ? "" : body.query().trim();
        }
        if (keyword.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_keyword", "keyword 不能为空");
        }
        return evals.rag(keyword, body.topK() == null ? 5 : body.topK());
    }

    @PostMapping("/api/v1/eval/products")
    public Map<String, Object> products(@RequestBody(required = false) ProductRequest body,
                                        HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        String query = body.query() == null ? "" : body.query().trim();
        if (query.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_query", "query 不能为空");
        }
        return evals.products(query, body.limit() == null ? 10 : body.limit());
    }

    @PostMapping("/api/v1/eval/image-search")
    public ImageSearchService.ImageSearchResponse image(
            @RequestBody(required = false) ImageRequest body, HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        return evals.image(body.fileId(), body.objectKey(), body.imageUrl(),
                body.topK() == null ? 0 : body.topK());
    }

    @GetMapping("/api/v1/admin/evals")
    public Map<String, Object> dashboard(HttpServletRequest request) {
        requireAdmin(request);
        return evals.dashboard();
    }

    @GetMapping("/api/v1/admin/evals/reports/{runId}/{fileName}")
    public JsonNode report(@PathVariable String runId, @PathVariable String fileName,
                           HttpServletRequest request) {
        requireAdmin(request);
        if (runId.contains("..") || fileName.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_report_id", "报告 ID 不合法");
        }
        try {
            return evals.report(runId, fileName);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "report_not_found", "报告不存在");
        }
    }

    private static Account requireAdmin(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"admin".equals(account.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是管理员");
        }
        return account;
    }

    private static ApiException badRequestBody() {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
    }
}
