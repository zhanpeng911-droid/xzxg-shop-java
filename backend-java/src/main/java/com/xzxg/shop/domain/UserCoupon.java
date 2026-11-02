package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record UserCoupon(
        @JsonProperty("user_coupon_id") String userCouponId,
        @JsonProperty("coupon_id") String couponId,
        @JsonProperty("account_id") String accountId,
        String status,
        @JsonProperty("order_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String orderId,
        @JsonProperty("claimed_at") String claimedAt,
        @JsonProperty("used_at") String usedAt,
        Coupon coupon) {
}
