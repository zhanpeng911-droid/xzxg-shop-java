package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CartSummary(
        @JsonProperty("selectedCount") int selectedCount,
        @JsonProperty("totalAmount") String totalAmount,
        @JsonProperty("discountAmount") String discountAmount,
        @JsonProperty("payAmount") String payAmount) {
}
