package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProductReview(
        @JsonProperty("review_id") String reviewId,
        @JsonProperty("order_id") String orderId,
        @JsonProperty("order_item_id") String orderItemId,
        @JsonProperty("product_id") String productId,
        @JsonProperty("sku_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String skuId,
        @JsonProperty("account_id") String accountId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String username,
        int rating,
        String content,
        List<String> tags,
        String status,
        @JsonProperty("merchant_reply") @JsonInclude(JsonInclude.Include.NON_EMPTY) String merchantReply,
        @JsonProperty("merchant_replied_at") String merchantRepliedAt,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}
