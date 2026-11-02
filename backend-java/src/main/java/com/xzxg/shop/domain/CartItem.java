package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record CartItem(
        @JsonProperty("cartItemId") String cartItemId,
        @JsonProperty("productId") String productId,
        @JsonProperty("skuId") @JsonInclude(JsonInclude.Include.NON_EMPTY) String skuId,
        String name,
        @JsonProperty("imageUrl") String imageUrl,
        String price,
        int quantity,
        boolean selected,
        @JsonProperty("stockStatus") String stockStatus,
        @JsonProperty("merchantId") String merchantId,
        @JsonProperty("merchantName") String merchantName) {
}
