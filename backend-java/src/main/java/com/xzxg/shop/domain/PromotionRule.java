package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record PromotionRule(
        @JsonProperty("promotion_id") String promotionId,
        String name,
        String scope,
        @JsonProperty("merchant_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String merchantId,
        @JsonProperty("product_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String productId,
        @JsonProperty("category_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String categoryId,
        String type,
        @JsonProperty("threshold_amount") String thresholdAmount,
        @JsonProperty("discount_amount") String discountAmount,
        @JsonProperty("discount_rate") String discountRate,
        boolean stackable,
        @JsonProperty("start_at") String startAt,
        @JsonProperty("end_at") String endAt,
        String status,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}
