package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Payment(
        @JsonProperty("payment_id") String paymentId,
        @JsonProperty("order_id") String orderId,
        @JsonProperty("account_id") String accountId,
        String amount,
        String status,
        String method,
        @JsonProperty("transaction_no") @JsonInclude(JsonInclude.Include.NON_EMPTY) String transactionNo,
        @JsonProperty("expires_at") String expiresAt,
        @JsonProperty("paid_at") String paidAt,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}
