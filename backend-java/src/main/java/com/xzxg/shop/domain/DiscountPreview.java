package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DiscountPreview(
        @JsonProperty("total_amount") String totalAmount,
        @JsonProperty("discount_amount") String discountAmount,
        @JsonProperty("pay_amount") String payAmount,
        List<DiscountLine> lines) {
}
