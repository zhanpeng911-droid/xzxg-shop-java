package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Account(
        @JsonProperty("account_id") String accountId,
        String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("avatar_url") @JsonInclude(JsonInclude.Include.NON_EMPTY) String avatarUrl,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String phone,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String email,
        String role,
        @JsonProperty("merchant_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String merchantId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String status,
        @JsonProperty("created_at") String createdAt) {
}
