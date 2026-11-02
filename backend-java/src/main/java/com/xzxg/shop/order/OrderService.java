package com.xzxg.shop.order;

import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.domain.Order;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    public PageResponse<Order> userOrders(String accountId, PageRequest page) {
        return orders.listUserOrders(accountId, page);
    }

    public List<Order> checkout(String accountId) {
        return orders.createOrderFromCart(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "empty_cart", "请选择购物车商品后再下单"));
    }

    public Order get(String accountId, String orderId) {
        return orders.getOrder(accountId, orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "order_not_found", "订单不存在"));
    }

    public OrderRepository.PayResult pay(String accountId, String orderId, String method) {
        return orders.pay(accountId, orderId, method)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "pay_failed",
                        "订单不可支付，可能已支付、取消或超时关闭"));
    }

    public Order cancel(String accountId, String orderId, String reason) {
        return orders.cancel(accountId, orderId, reason)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "cancel_failed",
                        "订单不可取消，只有待支付订单可以取消"));
    }

    public Order confirmReceipt(String accountId, String orderId) {
        return orders.confirmReceipt(accountId, orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "confirm_failed",
                        "订单不可确认收货，只有已发货订单可以确认"));
    }

    public PageResponse<Order> merchantOrders(String merchantId, PageRequest page) {
        return orders.listMerchantOrders(merchantId, page);
    }

    public Order updateMerchantOrder(String merchantId, String orderId, String status) {
        return orders.updateOrderStatus(merchantId, orderId, status)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "order_not_found", "订单不存在或不属于当前商家"));
    }

    public PageResponse<Order> allOrders(PageRequest page) {
        return orders.listAllOrders(page);
    }

    public Order updateAdminOrder(String orderId, String status) {
        return orders.updateOrderStatus("", orderId, status)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "order_status_update_failed", "订单不存在或当前状态不允许更新"));
    }
}
