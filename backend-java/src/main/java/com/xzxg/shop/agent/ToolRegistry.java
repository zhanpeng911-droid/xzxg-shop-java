package com.xzxg.shop.agent;

import com.xzxg.shop.cart.CartService;
import com.xzxg.shop.catalog.CatalogService;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.AgentBlock;
import com.xzxg.shop.domain.Cart;
import com.xzxg.shop.domain.Coupon;
import com.xzxg.shop.domain.DiscountPreview;
import com.xzxg.shop.domain.Order;
import com.xzxg.shop.domain.ProductCard;
import com.xzxg.shop.domain.ProductReview;
import com.xzxg.shop.domain.ProductReviewInput;
import com.xzxg.shop.domain.PromotionRule;
import com.xzxg.shop.domain.UserCoupon;
import com.xzxg.shop.image.ImageSearchService;
import com.xzxg.shop.knowledge.KnowledgeService;
import com.xzxg.shop.order.OrderService;
import com.xzxg.shop.promotion.PromotionService;
import com.xzxg.shop.review.ReviewService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** All 20 agent tools, reusing the same services as the HTTP endpoints. */
@Component
public class ToolRegistry {

    public record Observation(String tool, boolean ok, String message,
                              Map<String, Object> result, List<AgentBlock> blocks) {
        public static Observation fail(String tool, String message) {
            return new Observation(tool, false, message, Map.of(), List.of());
        }
    }

    private final CatalogService catalog;
    private final CartService carts;
    private final OrderService orders;
    private final PromotionService promotions;
    private final ReviewService reviews;
    private final KnowledgeService knowledge;
    private final ImageSearchService images;
    private final ObjectMapper objectMapper;

    public ToolRegistry(CatalogService catalog, CartService carts, OrderService orders,
                        PromotionService promotions, ReviewService reviews,
                        KnowledgeService knowledge, ImageSearchService images,
                        ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.carts = carts;
        this.orders = orders;
        this.promotions = promotions;
        this.reviews = reviews;
        this.knowledge = knowledge;
        this.images = images;
        this.objectMapper = objectMapper;
    }

    public Observation execute(String name, Map<String, Object> args, Account account) {
        try {
            return switch (name) {
                case "search_products" -> searchProducts(args);
                case "search_image_products" -> searchImageProducts(args);
                case "search_knowledge" -> searchKnowledge(args);
                case "get_cart" -> getCart(account);
                case "add_cart_item" -> addCartItem(args, account);
                case "update_cart_item" -> updateCartItem(args, account);
                case "delete_cart_item" -> deleteCartItem(args, account);
                case "checkout" -> checkout(account);
                case "list_orders" -> listOrders(account);
                case "get_order" -> getOrder(args, account);
                case "pay_order" -> payOrder(args, account);
                case "cancel_order" -> cancelOrder(args, account);
                case "confirm_receipt" -> confirmReceipt(args, account);
                case "preview_discount" -> previewDiscount(account);
                case "list_coupons" -> listCoupons();
                case "list_user_coupons" -> listUserCoupons(account);
                case "claim_coupon" -> claimCoupon(args, account);
                case "list_promotions" -> listPromotions();
                case "list_product_reviews" -> listReviews(args);
                case "create_product_review" -> createReview(args, account);
                default -> Observation.fail(name, "未知工具：" + name);
            };
        } catch (ApiException ex) {
            return Observation.fail(name, ex.getMessage());
        } catch (RuntimeException ex) {
            return Observation.fail(name, "工具执行失败：" + ex.getMessage());
        }
    }

    private Observation searchProducts(Map<String, Object> args) {
        String query = str(args, "query");
        if (query.isEmpty()) {
            return Observation.fail("search_products", "query 不能为空");
        }
        int limit = Math.max(1, Math.min(intArg(args, "limit", 5), 20));
        PageResponse<ProductCard> page = catalog.products(query, null, PageRequest.of(1, limit));
        List<AgentBlock> blocks = new ArrayList<>();
        blocks.add(Blocks.productRefs(page.items().stream().map(ProductCard::productId).toList()));
        for (ProductCard card : page.items()) {
            blocks.add(Blocks.product(card));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.total());
        result.put("items", page.items());
        return new Observation("search_products", true,
                page.items().isEmpty() ? "没有找到匹配商品" : "找到 " + page.items().size() + " 个商品",
                result, blocks);
    }

    private Observation searchImageProducts(Map<String, Object> args) {
        ImageSearchService.ImageSearchResponse response = images.search(
                str(args, "file_id"), str(args, "object_key"), str(args, "image_url"),
                intArg(args, "top_k", 5));
        List<AgentBlock> blocks = new ArrayList<>();
        blocks.add(Blocks.productRefs(response.items().stream().map(ProductCard::productId).toList()));
        for (ProductCard card : response.items()) {
            blocks.add(Blocks.product(card));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("relevance_status", response.relevanceStatus());
        result.put("match_status", response.matchStatus());
        result.put("items", response.items());
        return new Observation("search_image_products", true,
                response.items().isEmpty() ? "没有找到相似商品" : "找到 " + response.items().size() + " 个相似商品",
                result, blocks);
    }

    private Observation searchKnowledge(Map<String, Object> args) {
        String query = str(args, "query");
        int topK = Math.max(1, Math.min(intArg(args, "top_k", 5), 20));
        var citations = knowledge.search(query, topK);
        List<AgentBlock> blocks = new ArrayList<>();
        blocks.add(Blocks.citationRefs(citations.stream().map(c -> c.chunkId()).toList()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", citations);
        return new Observation("search_knowledge", true,
                citations.isEmpty() ? "没有找到相关资料" : "找到 " + citations.size() + " 条资料",
                result, blocks);
    }

    private Observation getCart(Account account) {
        Cart cart = carts.get(account.accountId());
        return new Observation("get_cart", true, "已获取购物车", Map.of("cart", cart),
                List.of(Blocks.cart(cart)));
    }

    private Observation addCartItem(Map<String, Object> args, Account account) {
        String productId = str(args, "product_id");
        if (productId.isEmpty()) {
            return Observation.fail("add_cart_item",
                    "product_id 不能为空；如不确定商品，请先调用 search_products");
        }
        Cart cart = carts.add(account.accountId(), productId, str(args, "sku_id"),
                intArg(args, "quantity", 1));
        return new Observation("add_cart_item", true, "已加入购物车", Map.of("cart", cart),
                List.of(Blocks.cart(cart)));
    }

    private Observation updateCartItem(Map<String, Object> args, Account account) {
        String cartItemId = str(args, "cart_item_id");
        if (cartItemId.isEmpty()) {
            return Observation.fail("update_cart_item", "cart_item_id 不能为空");
        }
        Integer quantity = args.containsKey("quantity") ? intArg(args, "quantity", 0) : null;
        Boolean selected = args.containsKey("selected") ? boolArg(args, "selected", true) : null;
        Cart cart = carts.update(account.accountId(), cartItemId, quantity, selected);
        return new Observation("update_cart_item", true, "已更新购物车", Map.of("cart", cart),
                List.of(Blocks.cart(cart)));
    }

    private Observation deleteCartItem(Map<String, Object> args, Account account) {
        String cartItemId = str(args, "cart_item_id");
        if (cartItemId.isEmpty()) {
            return Observation.fail("delete_cart_item", "cart_item_id 不能为空");
        }
        Cart cart = carts.delete(account.accountId(), cartItemId);
        return new Observation("delete_cart_item", true, "已删除购物车项", Map.of("cart", cart),
                List.of(Blocks.cart(cart)));
    }

    private Observation checkout(Account account) {
        List<Order> created = orders.checkout(account.accountId());
        return new Observation("checkout", true, "已创建 " + created.size() + " 个订单",
                Map.of("items", created), List.of(Blocks.orders(created)));
    }

    private Observation listOrders(Account account) {
        PageResponse<Order> page = orders.userOrders(account.accountId(), PageRequest.of(1, 10));
        return new Observation("list_orders", true, "已获取订单",
                Map.of("items", page.items(), "total", page.total()),
                List.of(Blocks.orders(page.items())));
    }

    private Observation getOrder(Map<String, Object> args, Account account) {
        String orderId = str(args, "order_id");
        if (orderId.isEmpty()) {
            return Observation.fail("get_order", "order_id 不能为空");
        }
        Order order = orders.get(account.accountId(), orderId);
        return new Observation("get_order", true, "已获取订单", Map.of("order", order),
                List.of(Blocks.orders(List.of(order))));
    }

    private Observation payOrder(Map<String, Object> args, Account account) {
        String orderId = str(args, "order_id");
        if (orderId.isEmpty()) {
            return Observation.fail("pay_order", "order_id 不能为空");
        }
        var result = orders.pay(account.accountId(), orderId, str(args, "method"));
        return new Observation("pay_order", true, "支付成功",
                Map.of("order", result.order(), "payment", result.payment()),
                List.of(Blocks.orders(List.of(result.order()))));
    }

    private Observation cancelOrder(Map<String, Object> args, Account account) {
        String orderId = str(args, "order_id");
        if (orderId.isEmpty()) {
            return Observation.fail("cancel_order", "order_id 不能为空");
        }
        Order order = orders.cancel(account.accountId(), orderId, str(args, "reason"));
        return new Observation("cancel_order", true, "订单已取消", Map.of("order", order),
                List.of(Blocks.orders(List.of(order))));
    }

    private Observation confirmReceipt(Map<String, Object> args, Account account) {
        String orderId = str(args, "order_id");
        if (orderId.isEmpty()) {
            return Observation.fail("confirm_receipt", "order_id 不能为空");
        }
        Order order = orders.confirmReceipt(account.accountId(), orderId);
        return new Observation("confirm_receipt", true, "已确认收货", Map.of("order", order),
                List.of(Blocks.orders(List.of(order))));
    }

    private Observation previewDiscount(Account account) {
        DiscountPreview preview = carts.preview(account.accountId());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_amount", preview.totalAmount());
        summary.put("discount_amount", preview.discountAmount());
        summary.put("pay_amount", preview.payAmount());
        return new Observation("preview_discount", true, "已生成优惠预览",
                Map.of("preview", preview), List.of(Blocks.list("discount_preview",
                        preview.lines().stream().map(line -> toMap(line)).toList(), summary)));
    }

    private Observation listCoupons() {
        PageResponse<Coupon> page = promotions.coupons(PageRequest.of(1, 20));
        return new Observation("list_coupons", true, "已获取优惠券",
                Map.of("items", page.items()), List.of(Blocks.list("coupon_list",
                        page.items().stream().map(this::toMap).toList(), Map.of())));
    }

    private Observation listUserCoupons(Account account) {
        PageResponse<UserCoupon> page = promotions.userCoupons(account.accountId(),
                PageRequest.of(1, 20));
        return new Observation("list_user_coupons", true, "已获取我的优惠券",
                Map.of("items", page.items()), List.of(Blocks.list("coupon_list",
                        page.items().stream().map(this::toMap).toList(), Map.of())));
    }

    private Observation claimCoupon(Map<String, Object> args, Account account) {
        String couponId = str(args, "coupon_id");
        if (couponId.isEmpty()) {
            return Observation.fail("claim_coupon", "coupon_id 不能为空");
        }
        UserCoupon coupon = promotions.claim(account.accountId(), couponId);
        return new Observation("claim_coupon", true, "领取成功",
                Map.of("coupon", coupon), List.of(Blocks.list("coupon_list",
                        List.of(toMap(coupon)), Map.of())));
    }

    private Observation listPromotions() {
        PageResponse<PromotionRule> page = promotions.promotions("", PageRequest.of(1, 20));
        return new Observation("list_promotions", true, "已获取活动",
                Map.of("items", page.items()), List.of(Blocks.list("promotion_list",
                        page.items().stream().map(this::toMap).toList(), Map.of())));
    }

    private Observation listReviews(Map<String, Object> args) {
        String productId = str(args, "product_id");
        if (productId.isEmpty()) {
            return Observation.fail("list_product_reviews", "product_id 不能为空");
        }
        PageResponse<ProductReview> page = catalog.reviews(productId, PageRequest.of(1, 10));
        return new Observation("list_product_reviews", true, "已获取评价",
                Map.of("items", page.items()), List.of(Blocks.list("review_summary",
                        page.items().stream().map(this::toMap).toList(), Map.of())));
    }

    private Observation createReview(Map<String, Object> args, Account account) {
        String orderId = str(args, "order_id");
        String orderItemId = str(args, "order_item_id");
        if (orderId.isEmpty() || orderItemId.isEmpty()) {
            return Observation.fail("create_product_review", "order_id 和 order_item_id 不能为空");
        }
        @SuppressWarnings("unchecked")
        List<String> tags = args.get("tags") instanceof List<?> list
                ? (List<String>) list.stream().map(String::valueOf).toList() : List.of();
        ProductReview review = reviews.create(account.accountId(), orderId, orderItemId,
                new ProductReviewInput(intArg(args, "rating", 0), str(args, "content"), tags));
        return new Observation("create_product_review", true, "评价已提交",
                Map.of("review", review), List.of(Blocks.list("review_summary",
                        List.of(toMap(review)), Map.of())));
    }

    private Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {
        });
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
