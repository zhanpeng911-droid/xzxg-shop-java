package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProductUpsertInput(
        @JsonProperty("product_id") String productId,
        @JsonProperty("merchant_id") String merchantId,
        String name,
        String brand,
        @JsonProperty("category_id") String categoryId,
        @JsonProperty("image_url") String imageUrl,
        String price,
        @JsonProperty("market_price") String marketPrice,
        @JsonProperty("stock_quantity") Integer stockQuantity,
        @JsonProperty("stock_status") String stockStatus,
        List<String> tags,
        @JsonProperty("selling_points") List<String> sellingPoints,
        @JsonProperty("recommend_reason") String recommendReason,
        @JsonProperty("risk_notes") List<String> riskNotes,
        String description) {
}
