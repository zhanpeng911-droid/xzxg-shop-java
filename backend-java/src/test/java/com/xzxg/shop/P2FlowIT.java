package com.xzxg.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end P2 flow against a real MySQL schema: legacy SHA-256 login and upgrade,
 * opaque tokens, role policy, account profile, catalog, merchant product CRUD, and
 * admin status changes.
 *
 * Run with: mvnw -Dtest=P2FlowIT test
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql-it")
class P2FlowIT {

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
    void loginCatalogAccountAndProductFlows() throws Exception {
        // Legacy SHA-256 login, root object response, and password upgrade.
        HttpResponse<String> login = call("POST", "/api/v1/auth/login", null,
                "{\"username\":\"user\",\"password\":\"user123456\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode loginJson = json(login);
        String userToken = loginJson.get("token").asText();
        assertThat(userToken).startsWith("tok_");
        assertThat(loginJson.get("account").get("account_id").asText()).isEqualTo("acct_user_001");
        assertThat(loginJson.get("account").has("avatar_url")).isFalse();

        String upgradedHash = jdbc.queryForObject(
                "SELECT password_hash FROM accounts WHERE account_id = 'acct_user_001'", String.class);
        assertThat(upgradedHash).startsWith("$2");

        HttpResponse<String> me = call("GET", "/api/v1/auth/me", userToken, null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json(me).get("username").asText()).isEqualTo("user");

        // Public catalog: camelCase card fields and string money.
        HttpResponse<String> products = call("GET", "/api/v1/products", null, null);
        assertThat(products.statusCode()).isEqualTo(200);
        JsonNode productPage = json(products);
        assertThat(productPage.get("total").asInt()).isGreaterThanOrEqualTo(3);
        JsonNode firstProduct = productPage.get("items").get(0);
        assertThat(firstProduct.get("productId").asText()).isNotBlank();
        assertThat(firstProduct.get("price").isString()).isTrue();
        assertThat(firstProduct.has("product_id")).isFalse();

        HttpResponse<String> detail = call("GET", "/api/v1/products/p_001", null, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode detailJson = json(detail);
        assertThat(detailJson.get("productId").asText()).isEqualTo("p_001");
        assertThat(detailJson.get("imageUrls").isArray()).isTrue();
        assertThat(detailJson.get("stockQuantity").asInt()).isGreaterThan(0);

        assertThat(call("GET", "/api/v1/products/p_001/skus", null, null).statusCode())
                .isEqualTo(200);
        HttpResponse<String> reviews = call("GET", "/api/v1/products/p_001/reviews", null, null);
        assertThat(reviews.statusCode()).isEqualTo(200);
        assertThat(json(reviews).get("items").isArray()).isTrue();
        assertThat(json(call("GET", "/api/v1/categories/tree", null, null)).get("items").isArray())
                .isTrue();

        // Anonymous access to a user-only route is rejected by the auth filter.
        HttpResponse<String> anonymousCart = call("GET", "/api/v1/cart", null, null);
        assertThat(anonymousCart.statusCode()).isEqualTo(401);
        assertThat(json(anonymousCart).get("code").asText()).isEqualTo("unauthorized");

        // Registration, profile/contact updates, then logout invalidation.
        String username = "it_user_" + System.currentTimeMillis();
        HttpResponse<String> register = call("POST", "/api/v1/auth/register", null,
                "{\"username\":\"" + username + "\",\"password\":\"password123\","
                        + "\"display_name\":\"集成用户\"}");
        assertThat(register.statusCode()).isEqualTo(201);
        String newToken = json(register).get("token").asText();

        HttpResponse<String> profile = call("PATCH", "/api/v1/account/profile", newToken,
                "{\"display_name\":\"新昵称\"}");
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(json(profile).get("display_name").asText()).isEqualTo("新昵称");

        HttpResponse<String> contact = call("PATCH", "/api/v1/account/contact", newToken,
                "{\"phone\":\"13800000000\",\"email\":\"it@example.com\"}");
        assertThat(contact.statusCode()).isEqualTo(200);
        assertThat(json(contact).get("email").asText()).isEqualTo("it@example.com");

        assertThat(call("POST", "/api/v1/auth/logout", newToken, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/auth/me", newToken, null).statusCode()).isEqualTo(401);

        // Merchant product lifecycle.
        HttpResponse<String> merchantLogin = call("POST", "/api/v1/auth/login", null,
                "{\"username\":\"merchant\",\"password\":\"merchant123456\"}");
        assertThat(merchantLogin.statusCode()).isEqualTo(200);
        String merchantToken = json(merchantLogin).get("token").asText();

        HttpResponse<String> created = call("POST", "/api/v1/merchant/products", merchantToken,
                "{\"name\":\"集成测试商品\",\"brand\":\"IT\",\"category_id\":\"c_phone\","
                        + "\"price\":\"1999.00\",\"market_price\":\"2199.00\",\"stock_quantity\":7}");
        assertThat(created.statusCode()).isEqualTo(200);
        String productId = json(created).get("productId").asText();
        assertThat(productId).startsWith("prod_");

        HttpResponse<String> updated = call("PATCH", "/api/v1/merchant/products/" + productId,
                merchantToken, "{\"name\":\"集成测试商品改\",\"price\":\"1899.00\","
                        + "\"stock_quantity\":5}");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json(updated).get("name").asText()).isEqualTo("集成测试商品改");

        HttpResponse<String> crossRole = call("PATCH", "/api/v1/merchant/products/" + productId,
                userToken, "{\"name\":\"越权\"}");
        assertThat(crossRole.statusCode()).isEqualTo(403);
        assertThat(json(crossRole).get("code").asText()).isEqualTo("forbidden");

        HttpResponse<String> deleted = call("DELETE", "/api/v1/merchant/products/" + productId,
                merchantToken, null);
        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(json(deleted).get("status").asText()).isEqualTo("deleted");

        // Admin listing and status updates.
        HttpResponse<String> adminLogin = call("POST", "/api/v1/auth/login", null,
                "{\"username\":\"admin\",\"password\":\"admin123456\"}");
        assertThat(adminLogin.statusCode()).isEqualTo(200);
        String adminToken = json(adminLogin).get("token").asText();

        HttpResponse<String> adminAccounts = call("GET", "/api/v1/admin/accounts", adminToken, null);
        assertThat(adminAccounts.statusCode()).isEqualTo(200);
        assertThat(json(adminAccounts).get("total").asInt()).isGreaterThanOrEqualTo(3);

        HttpResponse<String> adminProducts = call("GET", "/api/v1/admin/products?page_size=100",
                adminToken, null);
        assertThat(adminProducts.statusCode()).isEqualTo(200);

        HttpResponse<String> statusChange = call("PATCH", "/api/v1/admin/products/" + productId,
                adminToken, "{\"status\":\"active\"}");
        assertThat(statusChange.statusCode()).isEqualTo(200);
        assertThat(json(statusChange).get("status").asText()).isEqualTo("active");

        HttpResponse<String> badStatus = call("PATCH", "/api/v1/admin/products/" + productId,
                adminToken, "{\"status\":\"nope\"}");
        assertThat(badStatus.statusCode()).isEqualTo(400);
        assertThat(json(badStatus).get("code").asText()).isEqualTo("bad_status");
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
