package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Citation(
        @JsonProperty("chunkId") String chunkId,
        String title,
        String snippet,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String source) {
}
