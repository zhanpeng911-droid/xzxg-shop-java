package com.xzxg.shop.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Go's {@code pagedPayload}: items/page/page_size/total at the root. */
public record PageResponse<T>(
        List<T> items,
        int page,
        @JsonProperty("page_size") int pageSize,
        int total) {
}
