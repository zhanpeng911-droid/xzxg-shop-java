package com.xzxg.shop.promotion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.Coupon;
import com.xzxg.shop.domain.PromotionRule;
import com.xzxg.shop.domain.PromotionRuleInput;
import com.xzxg.shop.domain.ProductReview;
import com.xzxg.shop.domain.UserCoupon;
import com.xzxg.shop.review.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PromotionController {

    private final PromotionService promotions;
    private final ReviewService reviews;

    public PromotionController(PromotionService promotions, ReviewService reviews) {
        this.promotions = promotions;
        this.reviews = reviews;
    }

    public record StatusRequest(String status) {
    }

    public record ReplyRequest(String reply) {
    }

    @GetMapping("/api/v1/promotions")
    public PageResponse<PromotionRule> listPromotions(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize) {
        return promotions.promotions("", PageRequest.of(page, pageSize));
    }

    @GetMapping("/api/v1/coupons/available")
    public PageResponse<Coupon> availableCoupons(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize) {
        return promotions.coupons(PageRequest.of(page, pageSize));
    }

    @GetMapping("/api/v1/coupons/mine")
    public PageResponse<UserCoupon> myCoupons(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        return promotions.userCoupons(requireUser(request).accountId(),
                PageRequest.of(page, pageSize));
    }

    @PostMapping("/api/v1/coupons/{couponAction}")
    public UserCoupon claim(@PathVariable String couponAction, HttpServletRequest request) {
        Account user = requireUser(request);
        int colon = couponAction.indexOf(':');
        if (colon <= 0 || !"claim".equals(couponAction.substring(colon + 1))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "接口不存在");
        }
        return promotions.claim(user.accountId(), couponAction.substring(0, colon));
    }

    @GetMapping("/api/v1/merchant/promotions")
    public PageResponse<PromotionRule> merchantPromotions(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        return promotions.promotions(merchant.merchantId(), PageRequest.of(page, pageSize));
    }

    @PostMapping("/api/v1/merchant/promotions")
    public PromotionRule createMerchantPromotion(
            @RequestBody(required = false) PromotionRuleInput body,
            HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        if (body == null) {
            throw badRequestBody();
        }
        return promotions.create(new PromotionRuleInput(body.name(), "merchant",
                merchant.merchantId(), body.productId(), body.categoryId(), body.type(),
                body.thresholdAmount(), body.discountAmount(), body.discountRate(),
                body.stackable(), body.startAt(), body.endAt(), body.status()));
    }

    @PatchMapping("/api/v1/merchant/promotions/{promotionId}")
    public PromotionRule updateMerchantPromotion(@PathVariable String promotionId,
                                                 @RequestBody(required = false) StatusRequest body,
                                                 HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (!allowedEnabledStatus(body.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_status", "促销状态不合法");
        }
        return promotions.updateStatus(promotionId, merchant.merchantId(), body.status());
    }

    @GetMapping("/api/v1/admin/promotions")
    public PageResponse<PromotionRule> adminPromotions(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        requireAdmin(request);
        return promotions.promotions("", PageRequest.of(page, pageSize));
    }

    @PostMapping("/api/v1/admin/promotions")
    public PromotionRule createAdminPromotion(
            @RequestBody(required = false) PromotionRuleInput body,
            HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        return promotions.create(body);
    }

    @PatchMapping("/api/v1/admin/promotions/{promotionId}")
    public PromotionRule updateAdminPromotion(@PathVariable String promotionId,
                                              @RequestBody(required = false) StatusRequest body,
                                              HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (!allowedEnabledStatus(body.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_status", "促销状态不合法");
        }
        return promotions.updateStatus(promotionId, "", body.status());
    }

    @GetMapping("/api/v1/merchant/reviews")
    public PageResponse<ProductReview> merchantReviews(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        return reviews.merchantReviews(merchant.merchantId(), PageRequest.of(page, pageSize));
    }

    @PostMapping("/api/v1/merchant/reviews/{reviewAction}")
    public ProductReview replyReview(@PathVariable String reviewAction,
                                     @RequestBody(required = false) ReplyRequest body,
                                     HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        int colon = reviewAction.indexOf(':');
        if (colon <= 0 || !"reply".equals(reviewAction.substring(colon + 1))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "接口不存在");
        }
        if (body == null) {
            throw badRequestBody();
        }
        return reviews.reply(merchant.merchantId(), reviewAction.substring(0, colon),
                body.reply());
    }

    @GetMapping("/api/v1/admin/reviews")
    public PageResponse<ProductReview> adminReviews(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        requireAdmin(request);
        return reviews.allReviews(PageRequest.of(page, pageSize));
    }

    @PatchMapping("/api/v1/admin/reviews/{reviewId}")
    public ProductReview updateReview(@PathVariable String reviewId,
                                      @RequestBody(required = false) StatusRequest body,
                                      HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        String status = body.status();
        if (!"visible".equals(status) && !"hidden".equals(status) && !"deleted".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_status", "评价状态不合法");
        }
        return reviews.updateStatus(reviewId, status);
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

    private static boolean allowedEnabledStatus(String status) {
        return "active".equals(status) || "inactive".equals(status);
    }

    private static ApiException badRequestBody() {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
    }
}
