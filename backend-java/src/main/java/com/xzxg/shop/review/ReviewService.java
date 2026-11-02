package com.xzxg.shop.review;

import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.domain.ProductReview;
import com.xzxg.shop.domain.ProductReviewInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReviewService {

    private final ReviewRepository reviews;

    public ReviewService(ReviewRepository reviews) {
        this.reviews = reviews;
    }

    public ProductReview create(String accountId, String orderId, String orderItemId,
                                ProductReviewInput input) {
        return reviews.create(accountId, orderId, orderItemId, input)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "review_failed",
                        "评价失败，只有已完成订单项可评价且不可重复评价"));
    }

    public PageResponse<ProductReview> merchantReviews(String merchantId, PageRequest page) {
        return reviews.merchantReviews(merchantId, page);
    }

    public ProductReview reply(String merchantId, String reviewId, String reply) {
        return reviews.reply(merchantId, reviewId, reply)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "review_not_found", "评价不存在或不属于当前商家"));
    }

    public PageResponse<ProductReview> allReviews(PageRequest page) {
        return reviews.allReviews(page);
    }

    public ProductReview updateStatus(String reviewId, String status) {
        return reviews.updateStatus(reviewId, status)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "review_not_found", "评价不存在"));
    }
}
