package com.xzxg.shop.domain;

import java.util.List;

public record ProductReviewInput(Integer rating, String content, List<String> tags) {
}
