package com.xzxg.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end P3 flow: cart, discount preview, multi-merchant checkout, mock payment,
 * shipment/receipt, review and reply, coupons, and promotion management.
 *
 * Run against a fresh database:
 *   mvnw -Dtest=P3FlowIT test
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql-it")
class P3FlowIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void cartCheckoutPaymentAndReviewFlow() throws Exception {
        String userToken = login("user", "user123456");
        String merchantToken = login("merchant", "merchant123456");
        String adminToken = login("admin", "admin123456");
        seedSecondMerchantProduct();

        // Add one item from each merchant, then verify cart totals.
        assertThat(addToCart(userToken, "p_001", 2).statusCode()).isEqualTo(200);
        HttpResponse<String> cart = addToCart(userToken, "p_it2", 1);
        assertThat(cart.statusCode()).isEqualTo(200);
        JsonNode cartJson = json(cart);
        assertThat(cartJson.get("summary").get("selectedCount").asInt()).isEqualTo(3);
        assertThat(cartJson.get("summary").get("totalAmount").asText()).isEqualTo("6098.00");

        HttpResponse<String> preview = call("GET", "/api/v1/cart/discount-preview",
                userToken, null);
        assertThat(preview.statusCode()).isEqualTo(200);
        JsonNode previewJson = json(preview);
        assertThat(previewJson.get("total_amount").asText()).isEqualTo("6098.00");
        // Platform 300-30 and merchant 1000-80 both apply.
        assertThat(previewJson.get("discount_amount").asText()).isEqualTo("110.00");
        assertThat(previewJson.get("pay_amount").asText()).isEqualTo("5988.00");
        assertThat(previewJson.get("lines").size()).isGreaterThanOrEqualTo(2);

        // Checkout splits by merchant and clears the selected cart.
        HttpResponse<String> checkout = call("POST", "/api/v1/orders:checkout",
                userToken, null);
        assertThat(checkout.statusCode()).isEqualTo(200);
        JsonNode checkoutJson = json(checkout);
        assertThat(checkoutJson.get("items").size()).isEqualTo(2);
        List<String> orderIds = new ArrayList<>();
        for (JsonNode order : checkoutJson.get("items")) {
            orderIds.add(order.get("order_id").asText());
            assertThat(order.get("status").asText()).isEqualTo("pending_payment");
            assertThat(order.get("discount_amount").asText()).isEqualTo("0.00");
            assertThat(order.get("items").size()).isGreaterThan(0);
        }
        assertThat(json(call("GET", "/api/v1/cart", userToken, null))
                .get("items").size()).isZero();

        // Pay the first order and drive it through shipment and receipt.
        String orderId = orderIds.get(0);
        HttpResponse<String> pay = call("POST", "/api/v1/orders/" + orderId + ":pay",
                userToken, "{\"method\":\"mock_balance\"}");
        assertThat(pay.statusCode()).isEqualTo(200);
        JsonNode payJson = json(pay);
        assertThat(payJson.get("order").get("status").asText()).isEqualTo("pending_ship");
        assertThat(payJson.get("payment").get("status").asText()).isEqualTo("success");

        HttpResponse<String> shipped = call("PATCH",
                "/api/v1/merchant/orders/" + orderId, merchantToken,
                "{\"status\":\"shipped\"}");
        assertThat(shipped.statusCode()).isEqualTo(200);
        assertThat(json(shipped).get("status").asText()).isEqualTo("shipped");

        HttpResponse<String> completed = call("POST",
                "/api/v1/orders/" + orderId + ":confirm-receipt", userToken, null);
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(json(completed).get("status").asText()).isEqualTo("completed");

        String orderItemId = payJson.get("order").get("items").get(0)
                .get("order_item_id").asText();
        HttpResponse<String> review = call("POST",
                "/api/v1/orders/" + orderId + "/items/" + orderItemId + ":review",
                userToken, "{\"rating\":5,\"content\":\"集成测试好评\",\"tags\":[\"好用\"]}");
        assertThat(review.statusCode()).isEqualTo(200);
        JsonNode reviewJson = json(review);
        String reviewId = reviewJson.get("review_id").asText();
        assertThat(reviewJson.get("status").asText()).isEqualTo("visible");

        // Duplicate review is rejected by the unique order-item constraint.
        HttpResponse<String> duplicateReview = call("POST",
                "/api/v1/orders/" + orderId + "/items/" + orderItemId + ":review",
                userToken, "{\"rating\":4,\"content\":\"重复评价\"}");
        assertThat(duplicateReview.statusCode()).isEqualTo(409);

        HttpResponse<String> reply = call("POST",
                "/api/v1/merchant/reviews/" + reviewId + ":reply", merchantToken,
                "{\"reply\":\"感谢支持\"}");
        assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(json(reply).get("merchant_reply").asText()).isEqualTo("感谢支持");

        HttpResponse<String> hide = call("PATCH", "/api/v1/admin/reviews/" + reviewId,
                adminToken, "{\"status\":\"hidden\"}");
        assertThat(hide.statusCode()).isEqualTo(200);
        assertThat(json(hide).get("status").asText()).isEqualTo("hidden");

        // Coupons: first claim succeeds, second hits the per-user limit.
        HttpResponse<String> claim = call("POST",
                "/api/v1/coupons/coupon_platform_001:claim", userToken, null);
        assertThat(claim.statusCode()).isEqualTo(200);
        assertThat(json(claim).get("status").asText()).isEqualTo("unused");
        HttpResponse<String> claimAgain = call("POST",
                "/api/v1/coupons/coupon_platform_001:claim", userToken, null);
        assertThat(claimAgain.statusCode()).isEqualTo(409);
        assertThat(json(claimAgain).get("code").asText()).isEqualTo("claim_failed");
        assertThat(json(call("GET", "/api/v1/coupons/mine", userToken, null))
                .get("total").asInt()).isGreaterThanOrEqualTo(1);

        // Merchant promotion create/disable, admin sees all orders.
        HttpResponse<String> promotion = call("POST", "/api/v1/merchant/promotions",
                merchantToken, "{\"name\":\"集成促销\",\"type\":\"full_reduction\","
                        + "\"threshold_amount\":\"100.00\",\"discount_amount\":\"10.00\","
                        + "\"start_at\":\"2026-01-01 00:00:00\",\"end_at\":\"2026-12-31 23:59:59\"}");
        assertThat(promotion.statusCode()).withFailMessage(promotion.body()).isEqualTo(200);
        String promotionId = json(promotion).get("promotion_id").asText();
        HttpResponse<String> disabled = call("PATCH",
                "/api/v1/merchant/promotions/" + promotionId, merchantToken,
                "{\"status\":\"inactive\"}");
        assertThat(disabled.statusCode()).isEqualTo(200);
        assertThat(json(disabled).get("status").asText()).isEqualTo("inactive");

        assertThat(call("GET", "/api/v1/admin/orders", adminToken, null).statusCode())
                .isEqualTo(200);
        assertThat(json(call("GET", "/api/v1/merchant/orders", merchantToken, null))
                .get("total").asInt()).isGreaterThanOrEqualTo(1);

        // Empty checkout is rejected.
        HttpResponse<String> emptyCheckout = call("POST", "/api/v1/orders:checkout",
                userToken, null);
        assertThat(emptyCheckout.statusCode()).isEqualTo(400);
        assertThat(json(emptyCheckout).get("code").asText()).isEqualTo("empty_cart");
    }

    private void seedSecondMerchantProduct() {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchants WHERE merchant_id = 'm_it2'", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO merchants (merchant_id, name, logo_url, description, service_phone, status)
                VALUES ('m_it2', '集成第二商家', '', '用于拆单验证', '', 'active')
                """);
        jdbc.update("""
                INSERT INTO products (
                    product_id, merchant_id, name, brand, category_id, image_url, image_urls_json,
                    price, market_price, stock_quantity, stock_status, tags_json,
                    selling_points_json, recommend_reason, risk_notes_json, attributes_json,
                    suitable_for_json, not_suitable_for_json, description, status, sort_order
                ) VALUES ('p_it2', 'm_it2', '集成商品二', 'IT', 'c_phone', 'https://example.com/it.png',
                    JSON_ARRAY('https://example.com/it.png'), 100.00, 120.00, 10, 'in_stock',
                    JSON_ARRAY('测试'), JSON_ARRAY('拆单'), '测试商品', JSON_ARRAY(), JSON_ARRAY(),
                    JSON_ARRAY(), JSON_ARRAY(), '测试', 'active', 200)
                """);
        jdbc.update("""
                INSERT INTO product_skus (sku_id, product_id, sku_name, price, stock_quantity,
                                          stock_status, specs_json, is_default)
                VALUES ('sku_p_it2', 'p_it2', '集成商品二 默认款', 100.00, 10, 'in_stock',
                        JSON_OBJECT('版本', '默认款'), TRUE)
                """);
    }

    private String login(String username, String password) throws Exception {
        HttpResponse<String> response = call("POST", "/api/v1/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).get("token").asText();
    }

    private HttpResponse<String> addToCart(String token, String productId, int quantity)
            throws Exception {
        return call("POST", "/api/v1/cart/items", token,
                "{\"product_id\":\"" + productId + "\",\"quantity\":" + quantity + "}");
    }

    private HttpResponse<String> call(String method, String path, String token, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }
}
