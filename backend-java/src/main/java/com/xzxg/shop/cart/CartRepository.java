package com.xzxg.shop.cart;

import com.xzxg.shop.common.Ids;
import com.xzxg.shop.domain.Cart;
import com.xzxg.shop.domain.CartItem;
import com.xzxg.shop.domain.CartSummary;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CartRepository {

    private final JdbcTemplate jdbc;

    public CartRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Cart loadCart(String accountId) {
        List<CartItem> items = jdbc.query("""
                SELECT c.cart_item_id, c.product_id, c.sku_id, p.name, p.image_url,
                       COALESCE(ps.price, p.price), c.quantity, c.selected,
                       COALESCE(ps.stock_status, p.stock_status), p.merchant_id, m.name
                FROM cart_items c
                JOIN products p ON p.product_id = c.product_id
                JOIN merchants m ON m.merchant_id = p.merchant_id
                LEFT JOIN product_skus ps ON ps.sku_id = c.sku_id
                WHERE c.account_id = ?
                ORDER BY c.created_at, c.cart_item_id
                """, (rs, rowNum) -> new CartItem(
                rs.getString(1),
                rs.getString(2),
                nullToEmpty(rs.getString(3)),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getInt(7),
                rs.getBoolean(8),
                rs.getString(9),
                rs.getString(10),
                rs.getString(11)), accountId);
        return buildCart(items);
    }

    public Optional<Cart> addItem(String accountId, String productId, String skuId, int quantity) {
        if (!productExists(productId)) {
            return Optional.empty();
        }
        int normalizedQuantity = quantity <= 0 ? 1 : quantity;
        String resolvedSku = skuId == null || skuId.isBlank() ? firstSku(productId) : skuId;
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO cart_items (cart_item_id, account_id, product_id, sku_id, quantity,
                                        selected, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, TRUE, ?, ?)
                ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity),
                    selected = TRUE, updated_at = VALUES(updated_at)
                """, Ids.next("cart"), accountId, productId, nullToEmpty(resolvedSku),
                normalizedQuantity, Timestamp.valueOf(now), Timestamp.valueOf(now));
        return Optional.of(loadCart(accountId));
    }

    public Optional<Cart> updateItem(String accountId, String cartItemId, Integer quantity,
                                     Boolean selected) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (quantity != null && quantity > 0) {
            sets.add("quantity = ?");
            args.add(quantity);
        }
        if (selected != null) {
            sets.add("selected = ?");
            args.add(selected);
        }
        if (sets.isEmpty()) {
            return Optional.of(loadCart(accountId));
        }
        sets.add("updated_at = ?");
        args.add(Timestamp.valueOf(LocalDateTime.now()));
        args.add(cartItemId);
        args.add(accountId);
        int affected = jdbc.update("UPDATE cart_items SET " + String.join(", ", sets)
                + " WHERE cart_item_id = ? AND account_id = ?", args.toArray());
        return affected == 0 ? Optional.empty() : Optional.of(loadCart(accountId));
    }

    public Optional<Cart> deleteItem(String accountId, String cartItemId) {
        int affected = jdbc.update(
                "DELETE FROM cart_items WHERE cart_item_id = ? AND account_id = ?",
                cartItemId, accountId);
        return affected == 0 ? Optional.empty() : Optional.of(loadCart(accountId));
    }

    private boolean productExists(String productId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE product_id = ?", Integer.class, productId);
        return count != null && count > 0;
    }

    private String firstSku(String productId) {
        try {
            return jdbc.queryForObject("""
                    SELECT sku_id FROM product_skus
                    WHERE product_id = ?
                    ORDER BY sku_id
                    LIMIT 1
                    """, String.class, productId);
        } catch (EmptyResultDataAccessException ex) {
            return "";
        }
    }

    private static Cart buildCart(List<CartItem> items) {
        double total = 0;
        int selectedCount = 0;
        for (CartItem item : items) {
            if (!item.selected()) {
                continue;
            }
            total += parseAmount(item.price()) * item.quantity();
            selectedCount += item.quantity();
        }
        String amount = String.format(Locale.ROOT, "%.2f", total);
        return new Cart(items, new CartSummary(selectedCount, amount, "0.00", amount));
    }

    private static double parseAmount(String value) {
        try {
            return Double.parseDouble(value == null ? "0" : value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
