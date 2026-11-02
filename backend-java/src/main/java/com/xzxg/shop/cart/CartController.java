package com.xzxg.shop.cart;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.Cart;
import com.xzxg.shop.domain.DiscountPreview;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CartController {

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    public record AddCartItemRequest(
            @JsonProperty("product_id") String productId,
            @JsonProperty("sku_id") String skuId,
            Integer quantity) {
    }

    public record UpdateCartItemRequest(Integer quantity, Boolean selected) {
    }

    @GetMapping("/api/v1/cart")
    public Cart cart(HttpServletRequest request) {
        return carts.get(requireUser(request).accountId());
    }

    @GetMapping("/api/v1/cart/discount-preview")
    public DiscountPreview preview(HttpServletRequest request) {
        return carts.preview(requireUser(request).accountId());
    }

    @PostMapping("/api/v1/cart/items")
    public Cart add(@RequestBody(required = false) AddCartItemRequest body,
                    HttpServletRequest request) {
        Account user = requireUser(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (body.productId() == null || body.productId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_product_id", "商品 ID 不能为空");
        }
        return carts.add(user.accountId(), body.productId(), body.skuId(),
                body.quantity() == null ? 0 : body.quantity());
    }

    @PatchMapping("/api/v1/cart/items/{cartItemId}")
    public Cart update(@PathVariable String cartItemId,
                       @RequestBody(required = false) UpdateCartItemRequest body,
                       HttpServletRequest request) {
        Account user = requireUser(request);
        if (body == null) {
            throw badRequestBody();
        }
        return carts.update(user.accountId(), cartItemId, body.quantity(), body.selected());
    }

    @DeleteMapping("/api/v1/cart/items/{cartItemId}")
    public Cart delete(@PathVariable String cartItemId, HttpServletRequest request) {
        return carts.delete(requireUser(request).accountId(), cartItemId);
    }

    private static Account requireUser(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"user".equals(account.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是用户端账号");
        }
        return account;
    }

    private static ApiException badRequestBody() {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
    }
}
