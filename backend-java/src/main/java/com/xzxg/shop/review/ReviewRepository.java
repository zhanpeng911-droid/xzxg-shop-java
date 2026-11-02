package com.xzxg.shop.review;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.common.Ids;
import com.xzxg.shop.common.JsonCodec;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.domain.ProductReview;
import com.xzxg.shop.domain.ProductReviewInput;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final RowMapper<ProductReview> mapper = (rs, rowNum) -> mapReview(rs);

    public ReviewRepository(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<ProductReview> create(String accountId, String orderId, String orderItemId,
                                          ProductReviewInput input) {
        if (input.rating() == null || input.rating() < 1 || input.rating() > 5
                || input.content() == null || input.content().trim().isEmpty()) {
            return Optional.empty();
        }
        List<OrderItemRow> rows = jdbc.query("""
                SELECT oi.product_id, oi.sku_id, o.status
                FROM order_items oi
                JOIN orders o ON o.order_id = oi.order_id
                WHERE oi.order_item_id = ? AND oi.order_id = ? AND o.account_id = ?
                """, (rs, rowNum) -> new OrderItemRow(
                rs.getString("product_id"), rs.getString("sku_id"), rs.getString("status")),
                orderItemId, orderId, accountId);
        if (rows.isEmpty() || !"completed".equals(rows.get(0).status())) {
            return Optional.empty();
        }
        OrderItemRow row = rows.get(0);
        LocalDateTime now = LocalDateTime.now();
        ProductReview review = new ProductReview(
                Ids.next("rev"), orderId, orderItemId, row.productId(),
                row.skuId() == null ? "" : row.skuId(), accountId, "", input.rating(),
                input.content().trim(), input.tags() == null ? List.of() : input.tags(),
                "visible", "", GoTime.format(null), GoTime.format(now), GoTime.format(now));
        try {
            jdbc.update("""
                    INSERT INTO product_reviews (
                        review_id, order_id, order_item_id, product_id, sku_id, account_id,
                        rating, content, tags_json, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, review.reviewId(), review.orderId(), review.orderItemId(),
                    review.productId(), review.skuId(), review.accountId(), review.rating(),
                    review.content(), json.write(review.tags()), review.status(),
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        return Optional.of(review);
    }

    public PageResponse<ProductReview> merchantReviews(String merchantId, PageRequest page) {
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM product_reviews r
                JOIN products p ON p.product_id = r.product_id
                WHERE p.merchant_id = ?
                """, Integer.class, merchantId);
        List<ProductReview> items = listReviews(
                "JOIN products p ON p.product_id = r.product_id WHERE p.merchant_id = ?",
                page.pageSize(), page.offset(), merchantId);
        return new PageResponse<>(items, page.page(), page.pageSize(), total == null ? 0 : total);
    }

    public Optional<ProductReview> reply(String merchantId, String reviewId, String rawReply) {
        String reply = rawReply == null ? "" : rawReply.trim();
        if (reply.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        int affected = jdbc.update("""
                UPDATE product_reviews r
                JOIN products p ON p.product_id = r.product_id
                SET r.merchant_reply = ?, r.merchant_replied_at = ?, r.updated_at = ?
                WHERE r.review_id = ? AND p.merchant_id = ?
                """, truncate(reply, 1000), Timestamp.valueOf(now), Timestamp.valueOf(now),
                reviewId, merchantId);
        if (affected == 0) {
            return Optional.empty();
        }
        return listReviews("WHERE r.review_id = ?", 0, 0, reviewId).stream().findFirst();
    }

    public PageResponse<ProductReview> allReviews(PageRequest page) {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM product_reviews", Integer.class);
        List<ProductReview> items = listReviews("", page.pageSize(), page.offset());
        return new PageResponse<>(items, page.page(), page.pageSize(), total == null ? 0 : total);
    }

    public Optional<ProductReview> updateStatus(String reviewId, String status) {
        int affected = jdbc.update("""
                UPDATE product_reviews SET status = ?, updated_at = ?
                WHERE review_id = ?
                """, status, Timestamp.valueOf(LocalDateTime.now()), reviewId);
        if (affected == 0) {
            return Optional.empty();
        }
        return listReviews("WHERE r.review_id = ?", 0, 0, reviewId).stream().findFirst();
    }

    private List<ProductReview> listReviews(String where, int limit, int offset, Object... args) {
        String query = """
                SELECT r.review_id, r.order_id, r.order_item_id, r.product_id, r.sku_id,
                       r.account_id, COALESCE(a.username, '') AS username, r.rating, r.content,
                       r.tags_json, r.status, COALESCE(r.merchant_reply, '') AS merchant_reply,
                       r.merchant_replied_at, r.created_at, r.updated_at
                FROM product_reviews r
                LEFT JOIN accounts a ON a.account_id = r.account_id
                """;
        if (where != null && !where.isBlank()) {
            query += " " + where;
        }
        query += " ORDER BY r.created_at DESC, r.review_id DESC";
        Object[] queryArgs;
        if (limit > 0) {
            query += " LIMIT ? OFFSET ?";
            queryArgs = new Object[args.length + 2];
            System.arraycopy(args, 0, queryArgs, 0, args.length);
            queryArgs[args.length] = limit;
            queryArgs[args.length + 1] = offset;
        } else {
            queryArgs = args;
        }
        return jdbc.query(query, mapper, queryArgs);
    }

    private ProductReview mapReview(ResultSet rs) throws SQLException {
        return new ProductReview(
                rs.getString("review_id"),
                rs.getString("order_id"),
                rs.getString("order_item_id"),
                rs.getString("product_id"),
                nullToEmpty(rs.getString("sku_id")),
                rs.getString("account_id"),
                nullToEmpty(rs.getString("username")),
                rs.getInt("rating"),
                rs.getString("content"),
                json.stringList(rs.getString("tags_json")),
                rs.getString("status"),
                nullToEmpty(rs.getString("merchant_reply")),
                GoTime.format(timestamp(rs, "merchant_replied_at")),
                GoTime.format(timestamp(rs, "created_at")),
                GoTime.format(timestamp(rs, "updated_at")));
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxRunes) {
        if (value.codePointCount(0, value.length()) <= maxRunes) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxRunes));
    }

    private record OrderItemRow(String productId, String skuId, String status) {
    }
}
