package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PromotionRuleInput(
        String name,
        String scope,
        @JsonProperty("merchant_id") String merchantId,
        @JsonProperty("product_id") String productId,
        @JsonProperty("category_id") String categoryId,
        String type,
        @JsonProperty("threshold_amount") String thresholdAmount,
        @JsonProperty("discount_amount") String discountAmount,
        @JsonProperty("discount_rate") String discountRate,
        Boolean stackable,
        @JsonProperty("start_at") String startAt,
        @JsonProperty("end_at") String endAt,
        String status) {
}
