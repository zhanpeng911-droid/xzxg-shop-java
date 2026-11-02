package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record OrderItem(
        @JsonProperty("order_item_id") String orderItemId,
        @JsonProperty("product_id") String productId,
        @JsonProperty("sku_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String skuId,
        String name,
        @JsonProperty("image_url") String imageUrl,
        String price,
        int quantity,
        @JsonProperty("merchant_id") String merchantId,
        @JsonProperty("merchant_name") String merchantName) {
}
