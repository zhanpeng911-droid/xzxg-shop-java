package com.xzxg.shop.order;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.common.Ids;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.domain.CartItem;
import com.xzxg.shop.domain.Order;
import com.xzxg.shop.domain.OrderItem;
import com.xzxg.shop.domain.Payment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OrderRepository {

    private static final DateTimeFormatter ORDER_NO_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public OrderRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public record PayResult(Order order, Payment payment) {
    }

    public PageResponse<Order> listUserOrders(String accountId, PageRequest page) {
        expirePendingOrders();
        int total = count("SELECT COUNT(*) FROM orders WHERE account_id = ?", accountId);
        List<Order> items = listOrders(" WHERE o.account_id = ?", page.pageSize(), page.offset(),
                accountId);
        return new PageResponse<>(items, page.page(), page.pageSize(), total);
    }

    public PageResponse<Order> listMerchantOrders(String merchantId, PageRequest page) {
        expirePendingOrders();
        int total = count("SELECT COUNT(*) FROM orders WHERE merchant_id = ?", merchantId);
        List<Order> items = listOrders(" WHERE o.merchant_id = ?", page.pageSize(), page.offset(),
                merchantId);
        return new PageResponse<>(items, page.page(), page.pageSize(), total);
    }

    public PageResponse<Order> listAllOrders(PageRequest page) {
        expirePendingOrders();
        int total = count("SELECT COUNT(*) FROM orders");
        List<Order> items = listOrders("", page.pageSize(), page.offset());
        return new PageResponse<>(items, page.page(), page.pageSize(), total);
    }

    public Optional<Order> getOrder(String accountId, String orderId) {
        expirePendingOrders();
        List<Order> orders = listOrders(" WHERE o.order_id = ? AND o.account_id = ?", 0, 0,
                orderId, accountId);
        return orders.stream().findFirst();
    }

    public Optional<Order> getOrderById(String orderId) {
        List<Order> orders = listOrders(" WHERE o.order_id = ?", 0, 0, orderId);
        return orders.stream().findFirst();
    }

    public Optional<List<Order>> createOrderFromCart(String accountId) {
        List<Order> result = transactions.execute(status -> {
            List<CartItem> selected = lockSelectedCartItems(accountId);
            if (selected.isEmpty()) {
                status.setRollbackOnly();
                return null;
            }
            for (CartItem item : selected) {
                if (!reserveStock(item.productId(), item.skuId(), item.quantity())) {
                    status.setRollbackOnly();
                    return null;
                }
            }

            Map<String, List<CartItem>> byMerchant = new LinkedHashMap<>();
            for (CartItem item : selected) {
                byMerchant.computeIfAbsent(item.merchantId(), key -> new ArrayList<>()).add(item);
            }

            List<Order> orders = new ArrayList<>();
            for (Map.Entry<String, List<CartItem>> entry : byMerchant.entrySet()) {
                String merchantId = entry.getKey();
                List<CartItem> items = entry.getValue();
                String orderId = Ids.next("ord");
                double total = 0;
                String merchantName = "";
                for (CartItem item : items) {
                    total += parseAmount(item.price()) * item.quantity();
                    merchantName = item.merchantName();
                }
                String amount = money(total);
                LocalDateTime createdAt = LocalDateTime.now();
                String orderNo = nextOrderNo(createdAt);
                LocalDateTime deadline = createdAt.plusMinutes(30);

                jdbc.update("""
                        INSERT INTO orders (
                            order_id, order_no, account_id, merchant_id, status, total_amount,
                            discount_amount, pay_amount, payment_deadline_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'pending_payment', ?, '0.00', ?, ?, ?, ?)
                        """, orderId, orderNo, accountId, merchantId, amount, amount,
                        Timestamp.valueOf(deadline), Timestamp.valueOf(createdAt),
                        Timestamp.valueOf(createdAt));

                jdbc.update("""
                        INSERT INTO payments (payment_id, order_id, account_id, amount, status, method,
                                              expires_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'pending', 'mock', ?, ?, ?)
                        """, Ids.next("pay"), orderId, accountId, amount,
                        Timestamp.valueOf(deadline), Timestamp.valueOf(createdAt),
                        Timestamp.valueOf(createdAt));

                List<OrderItem> orderItems = new ArrayList<>();
                for (CartItem item : items) {
                    OrderItem orderItem = new OrderItem(Ids.next("ord_item"), item.productId(),
                            item.skuId(), item.name(), item.imageUrl(), item.price(),
                            item.quantity(), item.merchantId(), item.merchantName());
                    jdbc.update("""
                            INSERT INTO order_items (order_item_id, order_id, product_id, sku_id, name,
                                                     image_url, price, quantity, merchant_id,
                                                     merchant_name, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, orderItem.orderItemId(), orderId, orderItem.productId(),
                            orderItem.skuId(), orderItem.name(), orderItem.imageUrl(),
                            orderItem.price(), orderItem.quantity(), orderItem.merchantId(),
                            orderItem.merchantName(), Timestamp.valueOf(createdAt));
                    orderItems.add(orderItem);
                }

                orders.add(new Order(orderId, orderNo, accountId, merchantId, merchantName,
                        "pending_payment", amount, "0.00", amount,
                        GoTime.format(deadline), GoTime.format(null), GoTime.format(null),
                        GoTime.format(null), "", orderItems,
                        GoTime.format(createdAt), GoTime.format(createdAt)));
            }

            jdbc.update("DELETE FROM cart_items WHERE account_id = ? AND selected = TRUE", accountId);
            return orders;
        });
        return Optional.ofNullable(result);
    }

    public Optional<PayResult> pay(String accountId, String orderId, String rawMethod) {
        expirePendingOrders();
        String method = rawMethod == null || rawMethod.isBlank() ? "mock_balance" : rawMethod.trim();
        Boolean paid = transactions.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            List<PaymentRow> rows = jdbc.query("""
                    SELECT pay_amount, payment_deadline_at
                    FROM orders
                    WHERE order_id = ? AND account_id = ? AND status = 'pending_payment'
                    FOR UPDATE
                    """, (rs, rowNum) -> {
                Timestamp deadline = rs.getTimestamp(2);
                return new PaymentRow(rs.getString(1),
                        deadline == null ? null : deadline.toLocalDateTime());
            }, orderId, accountId);
            if (rows.isEmpty()) {
                status.setRollbackOnly();
                return false;
            }
            PaymentRow row = rows.get(0);
            LocalDateTime deadline = row.deadline();
            if (deadline != null && !GoTime.zero().equals(deadline) && now.isAfter(deadline)) {
                jdbc.update("""
                        UPDATE orders
                        SET status = 'closed_timeout', closed_at = ?,
                            cancel_reason = '支付超时自动关闭', updated_at = ?
                        WHERE order_id = ?
                        """, Timestamp.valueOf(now), Timestamp.valueOf(now), orderId);
                jdbc.update("""
                        UPDATE payments SET status = 'expired', updated_at = ?
                        WHERE order_id = ? AND status = 'pending'
                        """, Timestamp.valueOf(now), orderId);
                return false;
            }
            jdbc.update("""
                    UPDATE orders SET status = 'pending_ship', paid_at = ?, updated_at = ?
                    WHERE order_id = ? AND account_id = ? AND status = 'pending_payment'
                    """, Timestamp.valueOf(now), Timestamp.valueOf(now), orderId, accountId);
            jdbc.update("""
                    UPDATE payments
                    SET status = 'success', method = ?, transaction_no = ?, paid_at = ?, updated_at = ?
                    WHERE order_id = ? AND account_id = ? AND status = 'pending'
                    """, method, Ids.next("txn"), Timestamp.valueOf(now), Timestamp.valueOf(now),
                    orderId, accountId);
            return true;
        });
        if (!Boolean.TRUE.equals(paid)) {
            return Optional.empty();
        }
        Optional<Order> order = getOrder(accountId, orderId);
        Optional<Payment> payment = latestPayment(accountId, orderId);
        if (order.isEmpty() || payment.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PayResult(order.get(), payment.get()));
    }

    public Optional<Order> cancel(String accountId, String orderId, String rawReason) {
        expirePendingOrders();
        String reason = rawReason == null || rawReason.isBlank() ? "用户取消" : rawReason.trim();
        LocalDateTime now = LocalDateTime.now();
        int affected = jdbc.update("""
                UPDATE orders
                SET status = 'canceled', closed_at = ?, cancel_reason = ?, updated_at = ?
                WHERE order_id = ? AND account_id = ? AND status = 'pending_payment'
                """, Timestamp.valueOf(now), truncate(reason, 256), Timestamp.valueOf(now),
                orderId, accountId);
        if (affected == 0) {
            return Optional.empty();
        }
        jdbc.update("""
                UPDATE payments SET status = 'failed', updated_at = ?
                WHERE order_id = ? AND account_id = ? AND status = 'pending'
                """, Timestamp.valueOf(now), orderId, accountId);
        return getOrder(accountId, orderId);
    }

    public Optional<Order> confirmReceipt(String accountId, String orderId) {
        expirePendingOrders();
        LocalDateTime now = LocalDateTime.now();
        int affected = jdbc.update("""
                UPDATE orders SET status = 'completed', completed_at = ?, updated_at = ?
                WHERE order_id = ? AND account_id = ? AND status = 'shipped'
                """, Timestamp.valueOf(now), Timestamp.valueOf(now), orderId, accountId);
        return affected == 0 ? Optional.empty() : getOrder(accountId, orderId);
    }

    public int expirePendingOrders() {
        LocalDateTime now = LocalDateTime.now();
        int affected = jdbc.update("""
                UPDATE orders
                SET status = 'closed_timeout', closed_at = ?,
                    cancel_reason = '支付超时自动关闭', updated_at = ?
                WHERE status = 'pending_payment' AND payment_deadline_at IS NOT NULL
                  AND payment_deadline_at < ?
                """, Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));
        if (affected == 0) {
            return 0;
        }
        jdbc.update("""
                UPDATE payments p
                JOIN orders o ON o.order_id = p.order_id
                SET p.status = 'expired', p.updated_at = ?
                WHERE p.status = 'pending' AND o.status = 'closed_timeout'
                """, Timestamp.valueOf(now));
        return affected;
    }

    public Optional<Order> updateOrderStatus(String merchantId, String orderId, String status) {
        expirePendingOrders();
        LocalDateTime now = LocalDateTime.now();
        String sets = "status = ?, updated_at = ?";
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.add(Timestamp.valueOf(now));
        if ("completed".equals(status)) {
            sets += ", completed_at = ?";
            args.add(Timestamp.valueOf(now));
        }
        args.add(orderId);
        StringBuilder sql = new StringBuilder("UPDATE orders SET " + sets + " WHERE order_id = ?");
        if (merchantId != null && !merchantId.isBlank()) {
            sql.append(" AND merchant_id = ?");
            args.add(merchantId);
        }
        sql.append(" AND status IN ('pending_ship', 'shipped')");
        int affected = jdbc.update(sql.toString(), args.toArray());
        return affected == 0 ? Optional.empty() : getOrderById(orderId);
    }

    private List<Order> listOrders(String where, int limit, int offset, Object... args) {
        String suffix = limit > 0 ? " LIMIT ? OFFSET ?" : "";
        String sql = """
                SELECT o.order_id, o.order_no, o.account_id, o.merchant_id, m.name, o.status,
                       o.total_amount, o.discount_amount, o.pay_amount, o.payment_deadline_at,
                       o.paid_at, o.closed_at, o.completed_at, o.cancel_reason,
                       o.created_at, o.updated_at
                FROM orders o
                JOIN merchants m ON m.merchant_id = o.merchant_id
                """ + where + " ORDER BY o.created_at DESC, o.order_id DESC" + suffix;
        Object[] queryArgs;
        if (limit > 0) {
            queryArgs = new Object[args.length + 2];
            System.arraycopy(args, 0, queryArgs, 0, args.length);
            queryArgs[args.length] = limit;
            queryArgs[args.length + 1] = offset;
        } else {
            queryArgs = args;
        }
        return jdbc.query(sql, (rs, rowNum) -> mapOrder(rs), queryArgs);
    }

    private Order mapOrder(ResultSet rs) throws SQLException {
        Order order = new Order(
                rs.getString("order_id"),
                rs.getString("order_no"),
                rs.getString("account_id"),
                rs.getString("merchant_id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("total_amount"),
                rs.getString("discount_amount"),
                rs.getString("pay_amount"),
                GoTime.format(timestamp(rs, "payment_deadline_at")),
                GoTime.format(timestamp(rs, "paid_at")),
                GoTime.format(timestamp(rs, "closed_at")),
                GoTime.format(timestamp(rs, "completed_at")),
                nullToEmpty(rs.getString("cancel_reason")),
                new ArrayList<>(),
                GoTime.format(timestamp(rs, "created_at")),
                GoTime.format(timestamp(rs, "updated_at")));
        List<OrderItem> items = listOrderItems(order.orderId());
        return new Order(order.orderId(), order.orderNo(), order.accountId(), order.merchantId(),
                order.merchantName(), order.status(), order.totalAmount(), order.discountAmount(),
                order.payAmount(), order.paymentDeadlineAt(), order.paidAt(), order.closedAt(),
                order.completedAt(), order.cancelReason(), items, order.createdAt(),
                order.updatedAt());
    }

    private List<OrderItem> listOrderItems(String orderId) {
        return jdbc.query("""
                SELECT order_item_id, product_id, sku_id, name, image_url, price, quantity,
                       merchant_id, merchant_name
                FROM order_items
                WHERE order_id = ?
                ORDER BY order_item_id
                """, (rs, rowNum) -> new OrderItem(
                rs.getString("order_item_id"),
                rs.getString("product_id"),
                nullToEmpty(rs.getString("sku_id")),
                rs.getString("name"),
                rs.getString("image_url"),
                rs.getString("price"),
                rs.getInt("quantity"),
                rs.getString("merchant_id"),
                rs.getString("merchant_name")), orderId);
    }

    private Optional<Payment> latestPayment(String accountId, String orderId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT payment_id, order_id, account_id, amount, status, method,
                           transaction_no, expires_at, paid_at, created_at, updated_at
                    FROM payments
                    WHERE account_id = ? AND order_id = ?
                    ORDER BY created_at DESC, payment_id DESC
                    LIMIT 1
                    """, (rs, rowNum) -> new Payment(
                    rs.getString("payment_id"),
                    rs.getString("order_id"),
                    rs.getString("account_id"),
                    rs.getString("amount"),
                    rs.getString("status"),
                    rs.getString("method"),
                    nullToEmpty(rs.getString("transaction_no")),
                    GoTime.format(timestamp(rs, "expires_at")),
                    GoTime.format(timestamp(rs, "paid_at")),
                    GoTime.format(timestamp(rs, "created_at")),
                    GoTime.format(timestamp(rs, "updated_at"))), accountId, orderId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private List<CartItem> lockSelectedCartItems(String accountId) {
        return jdbc.query("""
                SELECT ci.cart_item_id, ci.product_id, ci.sku_id, ci.quantity, ci.selected,
                       p.name, p.image_url, COALESCE(ps.price, p.price) AS price,
                       p.stock_status, p.merchant_id, m.name AS merchant_name
                FROM cart_items ci
                JOIN products p ON p.product_id = ci.product_id AND p.status = 'active'
                JOIN merchants m ON m.merchant_id = p.merchant_id
                LEFT JOIN product_skus ps ON ps.product_id = p.product_id AND ps.sku_id = ci.sku_id
                WHERE ci.account_id = ? AND ci.selected = TRUE
                ORDER BY ci.created_at, ci.cart_item_id
                FOR UPDATE
                """, (rs, rowNum) -> {
            int quantity = rs.getInt("quantity");
            if (quantity <= 0) {
                throw new IllegalStateException("non-positive cart quantity");
            }
            return new CartItem(
                    rs.getString("cart_item_id"),
                    rs.getString("product_id"),
                    nullToEmpty(rs.getString("sku_id")),
                    rs.getString("name"),
                    rs.getString("image_url"),
                    rs.getString("price"),
                    quantity,
                    rs.getBoolean("selected"),
                    rs.getString("stock_status"),
                    rs.getString("merchant_id"),
                    rs.getString("merchant_name"));
        }, accountId);
    }

    private boolean reserveStock(String productId, String skuId, int quantity) {
        if (quantity <= 0) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (skuId != null && !skuId.isBlank()) {
            int affected = jdbc.update("""
                    UPDATE product_skus
                    SET stock_quantity = stock_quantity - ?,
                        stock_status = CASE WHEN stock_quantity - ? <= 0
                                            THEN 'out_of_stock' ELSE stock_status END,
                        updated_at = ?
                    WHERE product_id = ? AND sku_id = ? AND stock_quantity >= ?
                    """, quantity, quantity, Timestamp.valueOf(now), productId, skuId, quantity);
            if (affected == 0) {
                return false;
            }
        }
        return jdbc.update("""
                UPDATE products
                SET stock_quantity = stock_quantity - ?,
                    stock_status = CASE WHEN stock_quantity - ? <= 0
                                        THEN 'out_of_stock' ELSE stock_status END,
                    updated_at = ?
                WHERE product_id = ? AND stock_quantity >= ?
                """, quantity, quantity, Timestamp.valueOf(now), productId, quantity) > 0;
    }

    private int count(String sql, Object... args) {
        Integer total = jdbc.queryForObject(sql, Integer.class, args);
        return total == null ? 0 : total;
    }

    private static String nextOrderNo(LocalDateTime now) {
        return "NO" + ORDER_NO_TIME.format(now) + Ids.next("").substring(1);
    }

    private static double parseAmount(String value) {
        try {
            return Double.parseDouble(value == null ? "0" : value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String truncate(String value, int maxRunes) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= maxRunes) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, maxRunes));
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record PaymentRow(String amount, LocalDateTime deadline) {
    }
}
