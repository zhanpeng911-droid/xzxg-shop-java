package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AppConfig(
        @JsonProperty("config_key") String configKey,
        @JsonProperty("config_value") String configValue,
        @JsonProperty("value_type") String valueType,
        String description,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String domain,
        @JsonProperty("is_secret") boolean isSecret,
        @JsonProperty("updated_at") String updatedAt) {
}
