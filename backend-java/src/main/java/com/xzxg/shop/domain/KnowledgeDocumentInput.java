package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record KnowledgeDocumentInput(
        @JsonProperty("merchant_id") String merchantId,
        String title,
        @JsonProperty("doc_type") String docType,
        String content,
        @JsonProperty("source_url") String sourceUrl,
        @JsonProperty("content_hash") String contentHash,
        Map<String, Object> metadata,
        @JsonProperty("force_reindex") boolean forceReindex) {
}
