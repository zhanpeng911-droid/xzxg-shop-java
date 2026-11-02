package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record AgentBlock(
        String type,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String title,
        ProductCard product,
        @JsonProperty("product_ids") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> productIds,
        Citation citation,
        @JsonProperty("chunk_ids") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> chunkIds,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> columns,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Map<String, Object>> rows,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Map<String, Object>> items,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> summary,
        Cart cart,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Order> orders,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String code,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> action) {
}
