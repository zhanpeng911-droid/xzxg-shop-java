package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Merchant(
        @JsonProperty("merchantId") String merchantId,
        String name,
        @JsonProperty("logoUrl") String logoUrl,
        String description,
        @JsonProperty("servicePhone") @JsonInclude(JsonInclude.Include.NON_EMPTY) String servicePhone,
        String status) {
}
