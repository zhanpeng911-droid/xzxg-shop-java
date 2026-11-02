package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record UnstructuredIngestRequest(
        @JsonProperty("merchant_id") String merchantId,
        String title,
        @JsonProperty("source_url") String sourceUrl,
        @JsonProperty("source_type") String sourceType,
        String content,
        String html,
        @JsonProperty("json_text") String jsonText,
        Map<String, Object> metadata,
        @JsonProperty("force_reindex") boolean forceReindex) {
}
