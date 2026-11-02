package com.xzxg.shop.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.Order;
import com.xzxg.shop.domain.ProductReview;
import com.xzxg.shop.domain.ProductReviewInput;
import com.xzxg.shop.review.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orders;
    private final ReviewService reviews;

    public OrderController(OrderService orders, ReviewService reviews) {
        this.orders = orders;
        this.reviews = reviews;
    }

    public record PayRequest(String method) {
    }

    public record CancelRequest(String reason) {
    }

    public record StatusRequest(String status) {
    }

    @GetMapping("/api/v1/orders")
    public PageResponse<Order> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        return orders.userOrders(requireUser(request).accountId(), PageRequest.of(page, pageSize));
    }

    @PostMapping("/api/v1/orders:checkout")
    public Map<String, Object> checkout(HttpServletRequest request) {
        return Map.of("items", orders.checkout(requireUser(request).accountId()));
    }

    @GetMapping("/api/v1/orders/{orderId}")
    public Order get(@PathVariable String orderId, HttpServletRequest request) {
        return orders.get(requireUser(request).accountId(), orderId);
    }

    @PostMapping("/api/v1/orders/{orderAction}")
    public Object action(@PathVariable String orderAction,
                         @RequestBody(required = false) Map<String, Object> body,
                         HttpServletRequest request) {
        Account user = requireUser(request);
        int colon = orderAction.indexOf(':');
        if (colon <= 0 || colon == orderAction.length() - 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "接口不存在");
        }
        String orderId = orderAction.substring(0, colon);
        String action = orderAction.substring(colon + 1);
        if ("pay".equals(action)) {
            String method = body == null ? "" : stringValue(body.get("method"));
            OrderRepository.PayResult result = orders.pay(user.accountId(), orderId, method);
            return Map.of("order", result.order(), "payment", result.payment());
        }
        if ("cancel".equals(action)) {
            String reason = body == null ? "" : stringValue(body.get("reason"));
            return orders.cancel(user.accountId(), orderId, reason);
        }
        if ("confirm-receipt".equals(action)) {
            return orders.confirmReceipt(user.accountId(), orderId);
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "接口不存在");
    }

    @PostMapping("/api/v1/orders/{orderId}/items/{itemAction}")
    public ProductReview review(@PathVariable String orderId, @PathVariable String itemAction,
                                @RequestBody(required = false) ProductReviewInput body,
                                HttpServletRequest request) {
        Account user = requireUser(request);
        int colon = itemAction.indexOf(':');
        if (colon <= 0 || !"review".equals(itemAction.substring(colon + 1))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "接口不存在");
        }
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
        }
        return reviews.create(user.accountId(), orderId, itemAction.substring(0, colon), body);
    }

    @GetMapping("/api/v1/merchant/orders")
    public PageResponse<Order> merchantOrders(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        return orders.merchantOrders(merchant.merchantId(), PageRequest.of(page, pageSize));
    }

    @PatchMapping("/api/v1/merchant/orders/{orderId}")
    public Order merchantUpdate(@PathVariable String orderId,
                                @RequestBody(required = false) StatusRequest body,
                                HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (!allowedOrderStatus(body.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_status", "订单状态不合法");
        }
        return orders.updateMerchantOrder(merchant.merchantId(), orderId, body.status());
    }

    @GetMapping("/api/v1/admin/orders")
    public PageResponse<Order> adminOrders(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        requireAdmin(request);
        return orders.allOrders(PageRequest.of(page, pageSize));
    }

    @PatchMapping("/api/v1/admin/orders/{orderId}")
    public Order adminUpdate(@PathVariable String orderId,
                             @RequestBody(required = false) StatusRequest body,
                             HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (!allowedOrderStatus(body.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_status", "订单状态不合法");
        }
        return orders.updateAdminOrder(orderId, body.status());
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

    private static Account requireMerchant(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"merchant".equals(account.role()) || account.merchantId() == null
                || account.merchantId().isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是商家");
        }
        return account;
    }

    private static Account requireAdmin(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"admin".equals(account.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是管理员");
        }
        return account;
    }

    private static boolean allowedOrderStatus(String status) {
        return "pending_ship".equals(status) || "shipped".equals(status)
                || "completed".equals(status) || "canceled".equals(status)
                || "refund_requested".equals(status) || "refunded".equals(status);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static ApiException badRequestBody() {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
    }
}
