package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

public record DiscountLine(
        String type,
        String id,
        String name,
        String amount,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String description) {
}
