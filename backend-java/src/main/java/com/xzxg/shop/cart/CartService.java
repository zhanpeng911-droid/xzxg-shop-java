package com.xzxg.shop.cart;

import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.domain.Cart;
import com.xzxg.shop.domain.DiscountPreview;
import com.xzxg.shop.promotion.PromotionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CartService {

    private final CartRepository carts;
    private final PromotionService promotions;

    public CartService(CartRepository carts, PromotionService promotions) {
        this.carts = carts;
        this.promotions = promotions;
    }

    public Cart get(String accountId) {
        return carts.loadCart(accountId);
    }

    public Cart add(String accountId, String productId, String skuId, int quantity) {
        return carts.addItem(accountId, productId, skuId, quantity)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "product_not_found", "商品不存在"));
    }

    public Cart update(String accountId, String cartItemId, Integer quantity, Boolean selected) {
        return carts.updateItem(accountId, cartItemId, quantity, selected)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "cart_item_not_found", "购物车项不存在"));
    }

    public Cart delete(String accountId, String cartItemId) {
        return carts.deleteItem(accountId, cartItemId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "cart_item_not_found", "购物车项不存在"));
    }

    public DiscountPreview preview(String accountId) {
        return promotions.preview(accountId, carts.loadCart(accountId));
    }
}
