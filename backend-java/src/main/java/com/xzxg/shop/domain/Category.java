package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Category(
        @JsonProperty("categoryId") String categoryId,
        @JsonProperty("parentId") String parentId,
        String name,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Category> children) {
}
