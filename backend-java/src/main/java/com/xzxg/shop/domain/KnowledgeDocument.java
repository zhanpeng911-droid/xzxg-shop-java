package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record KnowledgeDocument(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("merchant_id") String merchantId,
        String title,
        @JsonProperty("doc_type") String docType,
        String status,
        @JsonProperty("chunk_count") int chunkCount,
        @JsonProperty("source_url") @JsonInclude(JsonInclude.Include.NON_EMPTY) String sourceUrl,
        @JsonProperty("content_hash") @JsonInclude(JsonInclude.Include.NON_EMPTY) String contentHash,
        @JsonProperty("created_at") String createdAt) {
}
