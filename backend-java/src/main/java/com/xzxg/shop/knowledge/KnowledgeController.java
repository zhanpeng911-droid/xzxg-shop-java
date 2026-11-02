package com.xzxg.shop.knowledge;

import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.KnowledgeDocument;
import com.xzxg.shop.domain.KnowledgeDocumentInput;
import com.xzxg.shop.domain.UnstructuredIngestRequest;
import com.xzxg.shop.domain.UnstructuredIngestResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KnowledgeController {

    private final KnowledgeService knowledge;

    public KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/api/v1/merchant/documents")
    public PageResponse<KnowledgeDocument> merchantDocuments(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        return knowledge.documents(merchant.merchantId(), PageRequest.of(page, pageSize), false);
    }

    @PostMapping("/api/v1/merchant/documents")
    public KnowledgeDocument createDocument(
            @RequestBody(required = false) KnowledgeDocumentInput body,
            HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (body.title() == null || body.title().trim().isEmpty()
                || body.content() == null || body.content().trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_document",
                    "资料标题和内容不能为空");
        }
        String docType = body.docType() == null || body.docType().isBlank()
                ? "product_detail" : body.docType();
        return knowledge.create(new KnowledgeDocumentInput(merchant.merchantId(), body.title(),
                docType, body.content(), body.sourceUrl(), body.contentHash(), body.metadata(),
                body.forceReindex()));
    }

    @PostMapping("/api/v1/merchant/unstructured-ingestions")
    public ResponseEntity<UnstructuredIngestResult> merchantIngest(
            @RequestBody(required = false) UnstructuredIngestRequest body,
            HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        if (body == null) {
            throw badRequestBody();
        }
        return ingest(new UnstructuredIngestRequest(merchant.merchantId(), body.title(),
                body.sourceUrl(), body.sourceType(), body.content(), body.html(),
                body.jsonText(), body.metadata(), body.forceReindex()));
    }

    @GetMapping("/api/v1/admin/documents")
    public PageResponse<KnowledgeDocument> adminDocuments(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        requireAdmin(request);
        return knowledge.documents("", PageRequest.of(page, pageSize), true);
    }

    @PostMapping("/api/v1/admin/unstructured-ingestions")
    public ResponseEntity<UnstructuredIngestResult> adminIngest(
            @RequestBody(required = false) UnstructuredIngestRequest body,
            HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (body.merchantId() == null || body.merchantId().trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_merchant_id",
                    "管理员入库必须指定 merchant_id");
        }
        return ingest(body);
    }

    private ResponseEntity<UnstructuredIngestResult> ingest(UnstructuredIngestRequest request) {
        UnstructuredIngestResult result;
        try {
            result = knowledge.ingest(request);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "parse_ingestion_failed", ex.getMessage());
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "create_ingestion_failed", "非结构化资料入库失败");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    private static Account requireMerchant(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"merchant".equals(account.role()) || account.merchantId() == null
                || account.merchantId().isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是商家");
        }
        return account;
    }

    private static void requireAdmin(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"admin".equals(account.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是管理员");
        }
    }

    private static ApiException badRequestBody() {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
    }
}
