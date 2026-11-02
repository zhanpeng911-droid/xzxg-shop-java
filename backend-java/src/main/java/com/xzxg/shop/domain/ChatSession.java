package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatSession(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("account_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String accountId,
        String title,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String summary,
        @JsonProperty("message_count") int messageCount,
        @JsonProperty("last_message_at") String lastMessageAt,
        @JsonProperty("pinned_at") String pinnedAt,
        @JsonProperty("deleted_at") String deletedAt,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}
