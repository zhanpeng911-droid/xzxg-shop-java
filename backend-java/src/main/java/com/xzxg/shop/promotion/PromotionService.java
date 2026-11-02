package com.xzxg.shop.promotion;

import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.domain.Cart;
import com.xzxg.shop.domain.CartItem;
import com.xzxg.shop.domain.Coupon;
import com.xzxg.shop.domain.DiscountLine;
import com.xzxg.shop.domain.DiscountPreview;
import com.xzxg.shop.domain.PromotionRule;
import com.xzxg.shop.domain.PromotionRuleInput;
import com.xzxg.shop.domain.UserCoupon;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PromotionService {

    private final PromotionRepository promotions;

    public PromotionService(PromotionRepository promotions) {
        this.promotions = promotions;
    }

    public PageResponse<PromotionRule> promotions(String merchantId, PageRequest page) {
        int total = promotions.countPromotions(merchantId);
        List<PromotionRule> items = promotions.listPromotions(merchantId, page.pageSize(), page.offset());
        return new PageResponse<>(items, page.page(), page.pageSize(), total);
    }

    public PromotionRule create(PromotionRuleInput input) {
        try {
            return promotions.createPromotion(input);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "create_promotion_failed", "创建促销失败");
        }
    }

    public PromotionRule updateStatus(String promotionId, String merchantId, String status) {
        return promotions.updatePromotionStatus(promotionId, merchantId, status)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "promotion_not_found",
                        merchantId == null || merchantId.isBlank()
                                ? "促销不存在" : "促销不存在或不属于当前商家"));
    }

    public PageResponse<Coupon> coupons(PageRequest page) {
        List<Coupon> all = promotions.listActiveCoupons();
        return paginate(all, page);
    }

    public PageResponse<UserCoupon> userCoupons(String accountId, PageRequest page) {
        return paginate(promotions.listUserCoupons(accountId), page);
    }

    public UserCoupon claim(String accountId, String couponId) {
        return promotions.claimCoupon(accountId, couponId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "claim_failed",
                        "优惠券不可领取，可能已领过、已过期或已领完"));
    }

    public DiscountPreview preview(String accountId, Cart cart) {
        double total = 0;
        Map<String, Double> merchantTotals = new HashMap<>();
        for (CartItem item : cart.items()) {
            if (!item.selected()) {
                continue;
            }
            double amount = parseAmount(item.price()) * item.quantity();
            total += amount;
            merchantTotals.merge(item.merchantId(), amount, Double::sum);
        }

        LocalDateTime now = LocalDateTime.now();
        List<DiscountLine> lines = new ArrayList<>();
        double discount = 0;
        for (PromotionRule rule : promotions.listPromotions("", 0, 0)) {
            if (!"active".equals(rule.status())) {
                continue;
            }
            LocalDateTime start = PromotionRepository.parseApiTime(rule.startAt(), now);
            LocalDateTime end = PromotionRepository.parseApiTime(rule.endAt(), now);
            if (now.isBefore(start) || now.isAfter(end)) {
                continue;
            }
            double base = "merchant".equals(rule.scope())
                    ? merchantTotals.getOrDefault(rule.merchantId(), 0.0)
                    : total;
            double lineAmount = PromotionRepository.discountAmountFor(base, rule.type(),
                    rule.thresholdAmount(), rule.discountAmount(), rule.discountRate());
            if (lineAmount <= 0) {
                continue;
            }
            discount += lineAmount;
            lines.add(new DiscountLine("promotion", rule.promotionId(), rule.name(),
                    PromotionRepository.money(lineAmount), ""));
        }

        for (UserCoupon userCoupon : promotions.listUserCoupons(accountId)) {
            Coupon coupon = userCoupon.coupon();
            if (!"unused".equals(userCoupon.status())
                    || !"active".equals(coupon.status())) {
                continue;
            }
            LocalDateTime start = PromotionRepository.parseApiTime(coupon.startAt(), now);
            LocalDateTime end = PromotionRepository.parseApiTime(coupon.endAt(), now);
            if (now.isBefore(start) || now.isAfter(end)) {
                continue;
            }
            double base = "merchant".equals(coupon.scope())
                    ? merchantTotals.getOrDefault(coupon.merchantId(), 0.0)
                    : total;
            double lineAmount = PromotionRepository.discountAmountFor(base, coupon.type(),
                    coupon.thresholdAmount(), coupon.discountAmount(), "0");
            if (lineAmount <= 0) {
                continue;
            }
            discount += lineAmount;
            lines.add(new DiscountLine("coupon", userCoupon.userCouponId(), coupon.name(),
                    PromotionRepository.money(lineAmount), ""));
        }

        if (discount > total) {
            discount = total;
        }
        return new DiscountPreview(
                PromotionRepository.money(total),
                PromotionRepository.money(discount),
                PromotionRepository.money(total - discount),
                lines);
    }

    private static <T> PageResponse<T> paginate(List<T> all, PageRequest page) {
        int total = all.size();
        int start = page.offset();
        if (start >= total) {
            return new PageResponse<>(List.of(), page.page(), page.pageSize(), total);
        }
        int end = Math.min(start + page.pageSize(), total);
        return new PageResponse<>(all.subList(start, end), page.page(), page.pageSize(), total);
    }

    private static double parseAmount(String value) {
        try {
            return Double.parseDouble(value == null ? "0" : value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
