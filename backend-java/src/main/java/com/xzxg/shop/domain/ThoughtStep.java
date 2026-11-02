package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ThoughtStep(
        String id,
        String title,
        String status,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String summary,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String detail,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ProductCard> products,
        @JsonProperty("order") @JsonInclude(JsonInclude.Include.NON_NULL) Integer order) {
}
