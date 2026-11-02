package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Order(
        @JsonProperty("order_id") String orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("account_id") String accountId,
        @JsonProperty("merchant_id") String merchantId,
        @JsonProperty("merchant_name") String merchantName,
        String status,
        @JsonProperty("total_amount") String totalAmount,
        @JsonProperty("discount_amount") String discountAmount,
        @JsonProperty("pay_amount") String payAmount,
        @JsonProperty("payment_deadline_at") String paymentDeadlineAt,
        @JsonProperty("paid_at") String paidAt,
        @JsonProperty("closed_at") String closedAt,
        @JsonProperty("completed_at") String completedAt,
        @JsonProperty("cancel_reason") @JsonInclude(JsonInclude.Include.NON_EMPTY) String cancelReason,
        List<OrderItem> items,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}
