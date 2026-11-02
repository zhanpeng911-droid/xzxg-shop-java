package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProductDetail(
        @JsonProperty("productId") String productId,
        @JsonProperty("skuId") @JsonInclude(JsonInclude.Include.NON_EMPTY) String skuId,
        @JsonProperty("merchantId") String merchantId,
        @JsonProperty("merchantName") String merchantName,
        String name,
        String brand,
        @JsonProperty("categoryId") String categoryId,
        @JsonProperty("imageUrl") String imageUrl,
        String price,
        @JsonProperty("marketPrice") @JsonInclude(JsonInclude.Include.NON_EMPTY) String marketPrice,
        @JsonProperty("stockStatus") String stockStatus,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String status,
        List<String> tags,
        @JsonProperty("sellingPoints") List<String> sellingPoints,
        @JsonProperty("recommendReason") String recommendReason,
        @JsonProperty("riskNotes") List<String> riskNotes,
        @JsonProperty("imageUrls") List<String> imageUrls,
        @JsonProperty("stockQuantity") int stockQuantity,
        List<ProductAttribute> attributes,
        @JsonProperty("suitableFor") List<String> suitableFor,
        @JsonProperty("notSuitableFor") List<String> notSuitableFor,
        String description) {
}
