package com.xzxg.shop.promotion;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.common.Ids;
import com.xzxg.shop.domain.Coupon;
import com.xzxg.shop.domain.PromotionRule;
import com.xzxg.shop.domain.PromotionRuleInput;
import com.xzxg.shop.domain.UserCoupon;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PromotionRepository {

    private static final DateTimeFormatter SPACE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RowMapper<PromotionRule> promotionMapper = (rs, rowNum) -> mapPromotion(rs);
    private final RowMapper<Coupon> couponMapper = (rs, rowNum) -> mapCoupon(rs);

    public PromotionRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public int countPromotions(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM promotion_rules", Integer.class);
            return total == null ? 0 : total;
        }
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM promotion_rules WHERE merchant_id = ?", Integer.class, merchantId);
        return total == null ? 0 : total;
    }

    public List<PromotionRule> listPromotions(String merchantId, int limit, int offset) {
        String where = merchantId == null || merchantId.isBlank() ? "" : " WHERE merchant_id = ?";
        String suffix = limit > 0 ? " LIMIT ? OFFSET ?" : "";
        String sql = """
                SELECT promotion_id, name, scope, merchant_id, product_id, category_id, type,
                       threshold_amount, discount_amount, discount_rate, stackable,
                       start_at, end_at, status, created_at, updated_at
                FROM promotion_rules
                """ + where + " ORDER BY created_at DESC, promotion_id DESC" + suffix;
        if (limit > 0) {
            return merchantId == null || merchantId.isBlank()
                    ? jdbc.query(sql, promotionMapper, limit, offset)
                    : jdbc.query(sql, promotionMapper, merchantId, limit, offset);
        }
        return merchantId == null || merchantId.isBlank()
                ? jdbc.query(sql, promotionMapper)
                : jdbc.query(sql, promotionMapper, merchantId);
    }

    public PromotionRule createPromotion(PromotionRuleInput input) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startAt = parseApiTime(input.startAt(), now);
        LocalDateTime endAt = parseApiTime(input.endAt(), now.plusDays(30));
        String scope = blankTo(input.scope(), "platform").trim();
        String type = blankTo(input.type(), "full_reduction").trim();
        String status = blankTo(input.status(), "active");
        String threshold = blankTo(input.thresholdAmount(), "0.00");
        String discountAmount = blankTo(input.discountAmount(), "0.00");
        String discountRate = blankTo(input.discountRate(), "0.0000");
        String name = input.name() == null ? "" : input.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("empty promotion name");
        }
        PromotionRule item = new PromotionRule(
                Ids.next("promo"), name, scope, trimToEmpty(input.merchantId()),
                trimToEmpty(input.productId()), trimToEmpty(input.categoryId()),
                type, threshold, discountAmount, discountRate,
                Boolean.TRUE.equals(input.stackable()),
                GoTime.format(startAt), GoTime.format(endAt), status,
                GoTime.format(now), GoTime.format(now));
        jdbc.update("""
                INSERT INTO promotion_rules (
                    promotion_id, name, scope, merchant_id, product_id, category_id, type,
                    threshold_amount, discount_amount, discount_rate, stackable,
                    start_at, end_at, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, item.promotionId(), item.name(), item.scope(), item.merchantId(),
                item.productId(), item.categoryId(), item.type(), item.thresholdAmount(),
                item.discountAmount(), item.discountRate(), item.stackable(),
                Timestamp.valueOf(startAt), Timestamp.valueOf(endAt), item.status(),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
        return item;
    }

    public Optional<PromotionRule> updatePromotionStatus(String promotionId, String merchantId,
                                                         String status) {
        int affected;
        if (merchantId == null || merchantId.isBlank()) {
            affected = jdbc.update("""
                    UPDATE promotion_rules SET status = ?, updated_at = ?
                    WHERE promotion_id = ?
                    """, status, Timestamp.valueOf(LocalDateTime.now()), promotionId);
        } else {
            affected = jdbc.update("""
                    UPDATE promotion_rules SET status = ?, updated_at = ?
                    WHERE promotion_id = ? AND merchant_id = ?
                    """, status, Timestamp.valueOf(LocalDateTime.now()), promotionId, merchantId);
        }
        if (affected == 0) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT promotion_id, name, scope, merchant_id, product_id, category_id, type,
                           threshold_amount, discount_amount, discount_rate, stackable,
                           start_at, end_at, status, created_at, updated_at
                    FROM promotion_rules WHERE promotion_id = ?
                    """, promotionMapper, promotionId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<Coupon> listActiveCoupons() {
        LocalDateTime now = LocalDateTime.now();
        return jdbc.query("""
                SELECT coupon_id, name, scope, merchant_id, type, threshold_amount, discount_amount,
                       total_count, claimed_count, per_user_limit, start_at, end_at, status,
                       created_at, updated_at
                FROM coupons
                WHERE status = 'active' AND start_at <= ? AND end_at >= ?
                ORDER BY created_at DESC, coupon_id DESC
                """, couponMapper, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    public List<UserCoupon> listUserCoupons(String accountId) {
        return jdbc.query("""
                SELECT uc.user_coupon_id, uc.coupon_id, uc.account_id, uc.status, uc.order_id,
                       uc.claimed_at, uc.used_at,
                       c.coupon_id, c.name, c.scope, c.merchant_id, c.type, c.threshold_amount,
                       c.discount_amount, c.total_count, c.claimed_count, c.per_user_limit,
                       c.start_at, c.end_at, c.status, c.created_at, c.updated_at
                FROM user_coupons uc
                JOIN coupons c ON c.coupon_id = uc.coupon_id
                WHERE uc.account_id = ?
                ORDER BY uc.claimed_at DESC, uc.user_coupon_id DESC
                """, (rs, rowNum) -> new UserCoupon(
                rs.getString("user_coupon_id"),
                rs.getString("coupon_id"),
                rs.getString("account_id"),
                rs.getString("status"),
                nullToEmpty(rs.getString("order_id")),
                GoTime.format(timestamp(rs, "claimed_at")),
                GoTime.format(timestamp(rs, "used_at")),
                mapCoupon(rs)), accountId);
    }

    public Optional<UserCoupon> claimCoupon(String accountId, String couponId) {
        UserCoupon result = transactions.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            Coupon coupon;
            try {
                coupon = jdbc.queryForObject("""
                        SELECT coupon_id, name, scope, merchant_id, type, threshold_amount,
                               discount_amount, total_count, claimed_count, per_user_limit,
                               start_at, end_at, status, created_at, updated_at
                        FROM coupons
                        WHERE coupon_id = ? AND status = 'active' AND start_at <= ? AND end_at >= ?
                        FOR UPDATE
                        """, couponMapper, couponId, Timestamp.valueOf(now), Timestamp.valueOf(now));
            } catch (EmptyResultDataAccessException ex) {
                status.setRollbackOnly();
                return null;
            }
            if (coupon == null) {
                status.setRollbackOnly();
                return null;
            }
            if (coupon.totalCount() > 0 && coupon.claimedCount() >= coupon.totalCount()) {
                status.setRollbackOnly();
                return null;
            }
            Integer owned = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_coupons WHERE account_id = ? AND coupon_id = ?
                    """, Integer.class, accountId, couponId);
            if (owned != null && owned >= coupon.perUserLimit()) {
                status.setRollbackOnly();
                return null;
            }
            String userCouponId = Ids.next("uc");
            jdbc.update("""
                    INSERT INTO user_coupons (user_coupon_id, coupon_id, account_id, status, claimed_at)
                    VALUES (?, ?, ?, 'unused', ?)
                    """, userCouponId, couponId, accountId, Timestamp.valueOf(now));
            jdbc.update("""
                    UPDATE coupons SET claimed_count = claimed_count + 1, updated_at = ?
                    WHERE coupon_id = ?
                    """, Timestamp.valueOf(now), couponId);
            Coupon claimed = new Coupon(coupon.couponId(), coupon.name(), coupon.scope(),
                    coupon.merchantId(), coupon.type(), coupon.thresholdAmount(),
                    coupon.discountAmount(), coupon.totalCount(), coupon.claimedCount() + 1,
                    coupon.perUserLimit(), coupon.startAt(), coupon.endAt(), coupon.status(),
                    coupon.createdAt(), coupon.updatedAt());
            return new UserCoupon(userCouponId, couponId, accountId, "unused", "",
                    GoTime.format(now), GoTime.format(null), claimed);
        });
        return Optional.ofNullable(result);
    }

    public static double discountAmountFor(double base, String discountType, String threshold,
                                           String fixedAmount, String rate) {
        double thresholdValue = parseAmount(threshold);
        if (thresholdValue > 0 && base < thresholdValue) {
            return 0;
        }
        if ("percentage".equals(discountType)) {
            double rateValue = parseAmount(rate);
            if (rateValue <= 0 || rateValue >= 1) {
                return 0;
            }
            return base * rateValue;
        }
        double amount = parseAmount(fixedAmount);
        if (amount < 0) {
            return 0;
        }
        return Math.min(amount, base);
    }

    public static String money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static double parseAmount(String value) {
        try {
            return Double.parseDouble(value == null ? "0" : value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static PromotionRule mapPromotion(ResultSet rs) throws SQLException {
        return new PromotionRule(
                rs.getString("promotion_id"),
                rs.getString("name"),
                rs.getString("scope"),
                nullToEmpty(rs.getString("merchant_id")),
                nullToEmpty(rs.getString("product_id")),
                nullToEmpty(rs.getString("category_id")),
                rs.getString("type"),
                rs.getString("threshold_amount"),
                rs.getString("discount_amount"),
                rs.getString("discount_rate"),
                rs.getBoolean("stackable"),
                GoTime.format(timestamp(rs, "start_at")),
                GoTime.format(timestamp(rs, "end_at")),
                rs.getString("status"),
                GoTime.format(timestamp(rs, "created_at")),
                GoTime.format(timestamp(rs, "updated_at")));
    }

    private static Coupon mapCoupon(ResultSet rs) throws SQLException {
        return new Coupon(
                rs.getString("coupon_id"),
                rs.getString("name"),
                rs.getString("scope"),
                nullToEmpty(rs.getString("merchant_id")),
                rs.getString("type"),
                rs.getString("threshold_amount"),
                rs.getString("discount_amount"),
                rs.getInt("total_count"),
                rs.getInt("claimed_count"),
                rs.getInt("per_user_limit"),
                GoTime.format(timestamp(rs, "start_at")),
                GoTime.format(timestamp(rs, "end_at")),
                rs.getString("status"),
                GoTime.format(timestamp(rs, "created_at")),
                GoTime.format(timestamp(rs, "updated_at")));
    }

    public static LocalDateTime parseApiTime(String input, LocalDateTime fallback) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value, SPACE_TIME);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(value).atStartOfDay();
        } catch (DateTimeParseException ex) {
            return fallback;
        }
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? GoTime.zero() : value.toLocalDateTime();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
