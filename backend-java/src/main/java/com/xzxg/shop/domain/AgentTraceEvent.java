package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AgentTraceEvent(
        @JsonProperty("trace_event_id") String traceEventId,
        @JsonProperty("run_id") String runId,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("account_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String accountId,
        String stage,
        @JsonProperty("event_type") String eventType,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String model,
        String status,
        @JsonProperty("duration_ms") @JsonInclude(JsonInclude.Include.NON_EMPTY) long durationMs,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String error,
        @JsonProperty("metadata_json") @JsonInclude(JsonInclude.Include.NON_EMPTY) String metadataJson,
        @JsonProperty("created_at") String createdAt) {
}
