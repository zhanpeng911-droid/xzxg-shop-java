package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record StoredFile(
        @JsonProperty("file_id") String fileId,
        @JsonProperty("account_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String accountId,
        @JsonProperty("object_key") String objectKey,
        String url,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("size_bytes") long sizeBytes,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("storage_provider") String storageProvider,
        @JsonProperty("source_url") @JsonInclude(JsonInclude.Include.NON_EMPTY) String sourceUrl,
        @JsonProperty("created_at") String createdAt) {
}
