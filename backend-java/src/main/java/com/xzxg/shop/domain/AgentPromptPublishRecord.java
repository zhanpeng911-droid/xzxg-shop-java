package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AgentPromptPublishRecord(
        @JsonProperty("record_id") String recordId,
        @JsonProperty("prompt_key") String promptKey,
        @JsonProperty("prompt_id") String promptId,
        int version,
        @JsonProperty("published_by") @JsonInclude(JsonInclude.Include.NON_EMPTY) String publishedBy,
        @JsonProperty("publish_target") String publishTarget,
        @JsonProperty("created_at") String createdAt) {
}
