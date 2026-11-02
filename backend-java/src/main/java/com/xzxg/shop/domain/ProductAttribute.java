package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ProductAttribute(
        String key,
        String value,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String unit) {
}
