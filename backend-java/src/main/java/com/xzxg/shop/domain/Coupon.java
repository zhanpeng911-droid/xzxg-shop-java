package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Coupon(
        @JsonProperty("coupon_id") String couponId,
        String name,
        String scope,
        @JsonProperty("merchant_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String merchantId,
        String type,
        @JsonProperty("threshold_amount") String thresholdAmount,
        @JsonProperty("discount_amount") String discountAmount,
        @JsonProperty("total_count") int totalCount,
        @JsonProperty("claimed_count") int claimedCount,
        @JsonProperty("per_user_limit") int perUserLimit,
        @JsonProperty("start_at") String startAt,
        @JsonProperty("end_at") String endAt,
        String status,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}
