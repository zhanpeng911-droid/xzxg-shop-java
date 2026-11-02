package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record UserMessageWithRuns(
        @JsonProperty("message_id") String messageId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("account_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String accountId,
        @JsonProperty("client_message_id") String clientMessageId,
        String content,
        List<Attachment> attachments,
        @JsonProperty("created_at") String createdAt,
        List<AgentRun> runs) {
}
