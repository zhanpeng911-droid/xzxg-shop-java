package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AgentPrompt(
        @JsonProperty("prompt_id") String promptId,
        @JsonProperty("prompt_key") String promptKey,
        String title,
        String content,
        String status,
        int version,
        String description,
        @JsonProperty("created_by") @JsonInclude(JsonInclude.Include.NON_EMPTY) String createdBy,
        @JsonProperty("published_at") String publishedAt,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}
