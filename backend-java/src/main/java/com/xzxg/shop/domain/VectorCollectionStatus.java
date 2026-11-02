package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VectorCollectionStatus(
        String name,
        String kind,
        @JsonProperty("primary_key") String primaryKey,
        @JsonProperty("vector_key") String vectorKey,
        @JsonProperty("metric_type") String metricType,
        int dimension,
        @JsonProperty("row_count") long rowCount,
        @JsonProperty("load_state") String loadState) {
}
