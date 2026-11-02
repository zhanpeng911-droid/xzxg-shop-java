package com.xzxg.shop.common.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Error root object kept compatible with the Go backend: {@code code/message/request_id}.
 * Success responses are never wrapped.
 */
public record ApiError(
        String code,
        String message,
        @JsonProperty("request_id") String requestId) {
}
