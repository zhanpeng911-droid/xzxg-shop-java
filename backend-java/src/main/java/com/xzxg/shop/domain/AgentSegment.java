package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

public record AgentSegment(
        String type,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String text,
        @JsonInclude(JsonInclude.Include.NON_NULL) AgentBlock block,
        @JsonInclude(JsonInclude.Include.NON_NULL) ThoughtStep thought) {
}
