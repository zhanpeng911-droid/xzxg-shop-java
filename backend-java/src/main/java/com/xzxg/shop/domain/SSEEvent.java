package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SSEEvent(
        String type,
        @JsonProperty("run_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String runId,
        @JsonProperty("session_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String sessionId,
        @JsonProperty("user_message_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String userMessageId,
        @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String traceId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String stage,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String text,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String delta,
        @JsonInclude(JsonInclude.Include.NON_NULL) AgentBlock block,
        @JsonInclude(JsonInclude.Include.NON_NULL) AgentBlock part,
        @JsonInclude(JsonInclude.Include.NON_NULL) ThoughtStep step,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> questions,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String code,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String message) {

    public static SSEEvent of(String type, String runId) {
        return new SSEEvent(type, runId, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public static SSEEvent delta(String type, String runId, String delta) {
        return new SSEEvent(type, runId, null, null, null, null, null, delta, null, null,
                null, null, null, null);
    }

    public static SSEEvent block(String runId, AgentBlock block) {
        return new SSEEvent("block_delta", runId, null, null, null, null, null, null,
                block, null, null, null, null, null);
    }

    public static SSEEvent error(String runId, String code, String message) {
        return new SSEEvent("error", runId, null, null, null, null, null, null, null, null,
                null, null, code, message);
    }
}
