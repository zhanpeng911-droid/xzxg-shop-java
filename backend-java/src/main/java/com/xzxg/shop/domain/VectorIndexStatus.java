package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record VectorIndexStatus(
        boolean enabled,
        boolean ready,
        String address,
        List<VectorCollectionStatus> collections,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String error,
        @JsonProperty("updated_at") String updatedAt) {
}
