package com.xzxg.shop.domain;

import java.util.List;

public record Cart(List<CartItem> items, CartSummary summary) {
}
