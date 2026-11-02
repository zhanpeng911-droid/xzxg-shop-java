package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record ProductSKU(
        @JsonProperty("skuId") String skuId,
        @JsonProperty("productId") String productId,
        @JsonProperty("skuName") String skuName,
        String price,
        @JsonProperty("stockQuantity") int stockQuantity,
        @JsonProperty("stockStatus") String stockStatus,
        Map<String, String> specs) {
}
