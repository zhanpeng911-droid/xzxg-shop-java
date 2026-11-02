package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AgentRun(
        @JsonProperty("run_id") String runId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("message_id") String messageId,
        @JsonProperty("account_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String accountId,
        String status,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("query_title") @JsonInclude(JsonInclude.Include.NON_EMPTY) String queryTitle,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AgentBlock> blocks,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> followups,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AgentSegment> segments) {
}
