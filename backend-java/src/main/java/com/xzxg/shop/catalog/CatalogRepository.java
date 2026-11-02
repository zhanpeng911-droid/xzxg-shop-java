package com.xzxg.shop.catalog;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.common.Ids;
import com.xzxg.shop.common.JsonCodec;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.QueryTerms;
import com.xzxg.shop.domain.Category;
import com.xzxg.shop.domain.Merchant;
import com.xzxg.shop.domain.ProductAttribute;
import com.xzxg.shop.domain.ProductCard;
import com.xzxg.shop.domain.ProductDetail;
import com.xzxg.shop.domain.ProductReview;
import com.xzxg.shop.domain.ProductSKU;
import com.xzxg.shop.domain.ProductUpsertInput;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

    private static final String PRODUCT_CARD_SELECT = """
            SELECT p.product_id, COALESCE(ps.sku_id, ''), p.merchant_id, m.name,
                p.name, p.brand, p.category_id, p.image_url, p.price, p.market_price,
                p.stock_status, p.status, p.tags_json, p.selling_points_json, p.recommend_reason,
                p.risk_notes_json
            FROM products p
            JOIN merchants m ON m.merchant_id = p.merchant_id
            LEFT JOIN categories c ON c.category_id = p.category_id
            LEFT JOIN product_skus ps ON ps.product_id = p.product_id AND ps.is_default = TRUE
            """;

    private static final String PRODUCT_DETAIL_SELECT = """
            SELECT p.product_id, COALESCE(ps.sku_id, ''), p.merchant_id, m.name,
                p.name, p.brand, p.category_id, p.image_url, p.price, p.market_price,
                p.stock_status, p.status, p.tags_json, p.selling_points_json, p.recommend_reason,
                p.risk_notes_json, p.image_urls_json, p.stock_quantity,
                p.attributes_json, p.suitable_for_json, p.not_suitable_for_json, p.description
            FROM products p
            JOIN merchants m ON m.merchant_id = p.merchant_id
            LEFT JOIN categories c ON c.category_id = p.category_id
            LEFT JOIN product_skus ps ON ps.product_id = p.product_id AND ps.is_default = TRUE
            """;

    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final RowMapper<ProductCard> cardMapper = (rs, rowNum) -> mapCard(rs);
    private final RowMapper<ProductDetail> detailMapper = (rs, rowNum) -> mapDetail(rs);
    private final RowMapper<Merchant> merchantMapper = (rs, rowNum) -> mapMerchant(rs);

    public CatalogRepository(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Category> listCategories() {
        List<Category> rows = jdbc.query("""
                SELECT category_id, COALESCE(parent_id, ''), name
                FROM categories
                ORDER BY sort_order, category_id
                """, (rs, rowNum) -> new Category(
                rs.getString(1), rs.getString(2), rs.getString(3), new ArrayList<>()));
        return buildCategoryTree(rows);
    }

    public PageResponse<Merchant> listMerchants(PageRequest page, boolean all) {
        String predicate = all ? "" : " WHERE status = 'active'";
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM merchants" + predicate, Integer.class);
        List<Merchant> items = jdbc.query("""
                SELECT merchant_id, name, logo_url, description, service_phone, status
                FROM merchants
                """ + predicate + " ORDER BY merchant_id LIMIT ? OFFSET ?",
                merchantMapper, page.pageSize(), page.offset());
        return new PageResponse<>(items, page.page(), page.pageSize(), total == null ? 0 : total);
    }

    public PageResponse<ProductCard> listProducts(String keyword, String categoryId,
                                                  PageRequest page, boolean all) {
        Query query = productListQuery(keyword, categoryId, all);
        Integer total = jdbc.queryForObject("SELECT COUNT(*)" + query.fromAndWhere(),
                Integer.class, query.args().toArray());
        List<Object> args = new ArrayList<>(query.args());
        args.add(page.pageSize());
        args.add(page.offset());
        List<ProductCard> items = jdbc.query(PRODUCT_CARD_SELECT + query.where()
                        + " ORDER BY p.sort_order, p.product_id LIMIT ? OFFSET ?",
                cardMapper, args.toArray());
        return new PageResponse<>(items, page.page(), page.pageSize(), total == null ? 0 : total);
    }

    public Optional<ProductDetail> findProduct(String productId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    PRODUCT_DETAIL_SELECT + " WHERE p.product_id = ?", detailMapper, productId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<ProductSKU> listProductSkus(String productId) {
        return jdbc.query("""
                SELECT sku_id, product_id, sku_name, price, stock_quantity, stock_status, specs_json
                FROM product_skus
                WHERE product_id = ?
                ORDER BY sku_id
                """, (rs, rowNum) -> new ProductSKU(
                rs.getString("sku_id"),
                rs.getString("product_id"),
                rs.getString("sku_name"),
                rs.getString("price"),
                rs.getInt("stock_quantity"),
                rs.getString("stock_status"),
                json.stringMap(rs.getString("specs_json"))), productId);
    }

    public List<ProductCard> productCardsByIds(List<String> productIds) {
        List<String> ids = productIds.stream().filter(id -> id != null && !id.isBlank())
                .distinct().limit(100).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(ids.size(), "?"));
        List<ProductCard> items = jdbc.query(PRODUCT_CARD_SELECT
                        + " WHERE p.status = 'active' AND m.status = 'active'"
                        + " AND p.product_id IN (" + placeholders + ")",
                cardMapper, ids.toArray());
        Map<String, ProductCard> byId = new LinkedHashMap<>();
        for (ProductCard item : items) {
            byId.put(item.productId(), item);
        }
        List<ProductCard> ordered = new ArrayList<>(items.size());
        for (String id : ids) {
            ProductCard item = byId.get(id);
            if (item != null) {
                ordered.add(item);
            }
        }
        return ordered;
    }

    public PageResponse<ProductReview> listProductReviews(String productId, PageRequest page) {
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM product_reviews
                WHERE product_id = ? AND status = 'visible'
                """, Integer.class, productId);
        List<ProductReview> items = jdbc.query("""
                SELECT r.review_id, r.order_id, r.order_item_id, r.product_id, r.sku_id, r.account_id,
                       COALESCE(a.username, '') AS username, r.rating, r.content, r.tags_json, r.status,
                       COALESCE(r.merchant_reply, '') AS merchant_reply, r.merchant_replied_at,
                       r.created_at, r.updated_at
                FROM product_reviews r
                LEFT JOIN accounts a ON a.account_id = r.account_id
                WHERE r.product_id = ? AND r.status = 'visible'
                ORDER BY r.created_at DESC, r.review_id DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new ProductReview(
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
                GoTime.format(timestamp(rs, "updated_at"))),
                productId, page.pageSize(), page.offset());
        return new PageResponse<>(items, page.page(), page.pageSize(), total == null ? 0 : total);
    }

    public ProductDetail createProduct(ProductUpsertInput raw) {
        ProductUpsertInput normalized = normalize(raw);
        String productId = normalized.productId() == null || normalized.productId().isBlank()
                ? Ids.next("prod")
                : normalized.productId();
        ProductUpsertInput input = withProductId(normalized, productId);
        insertProduct(input);
        upsertDefaultSku(input);
        return findProduct(productId).orElseThrow(
                () -> new IllegalStateException("created product not found: " + productId));
    }

    public Optional<ProductDetail> updateProduct(String productId, ProductUpsertInput raw) {
        ProductUpsertInput input = withProductId(normalize(raw), productId);
        int affected = jdbc.update("""
                UPDATE products
                SET name = ?, brand = ?, category_id = ?, image_url = ?, image_urls_json = ?,
                    price = ?, market_price = ?, stock_quantity = ?, stock_status = ?,
                    tags_json = ?, selling_points_json = ?, recommend_reason = ?,
                    risk_notes_json = ?, attributes_json = ?, suitable_for_json = ?,
                    not_suitable_for_json = ?, description = ?, updated_at = ?
                WHERE product_id = ? AND merchant_id = ?
                """,
                input.name(), input.brand(), input.categoryId(), input.imageUrl(),
                json.write(List.of(input.imageUrl())), input.price(), input.marketPrice(),
                input.stockQuantity(), input.stockStatus(), json.write(input.tags()),
                json.write(input.sellingPoints()), input.recommendReason(),
                json.write(input.riskNotes()), "[]", "[]", "[]", input.description(),
                Timestamp.valueOf(LocalDateTime.now()), productId, input.merchantId());
        if (affected == 0) {
            return Optional.empty();
        }
        upsertDefaultSku(input);
        return findProduct(productId);
    }

    public Optional<ProductDetail> updateProductStatus(String merchantId, String productId, String status) {
        int affected;
        if (merchantId == null || merchantId.isBlank()) {
            affected = jdbc.update("""
                    UPDATE products SET status = ?, updated_at = ?
                    WHERE product_id = ?
                    """, status, Timestamp.valueOf(LocalDateTime.now()), productId);
        } else {
            affected = jdbc.update("""
                    UPDATE products SET status = ?, updated_at = ?
                    WHERE product_id = ? AND merchant_id = ?
                    """, status, Timestamp.valueOf(LocalDateTime.now()), productId, merchantId);
        }
        return affected > 0 ? findProduct(productId) : Optional.empty();
    }

    public Optional<Merchant> updateMerchantStatus(String merchantId, String status) {
        int affected = jdbc.update("""
                UPDATE merchants SET status = ?, updated_at = ?
                WHERE merchant_id = ?
                """, status, Timestamp.valueOf(LocalDateTime.now()), merchantId);
        if (affected == 0) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT merchant_id, name, logo_url, description, service_phone, status
                    FROM merchants WHERE merchant_id = ?
                    """, merchantMapper, merchantId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private Query productListQuery(String keyword, String categoryId, boolean all) {
        String baseFrom = """
                 FROM products p
                 JOIN merchants m ON m.merchant_id = p.merchant_id
                 LEFT JOIN categories c ON c.category_id = p.category_id
                 LEFT JOIN product_skus ps ON ps.product_id = p.product_id AND ps.is_default = TRUE
                """;
        StringBuilder where = new StringBuilder(all
                ? " WHERE 1 = 1"
                : " WHERE p.status = 'active' AND m.status = 'active'");
        List<Object> args = new ArrayList<>();

        if (categoryId != null && !categoryId.isBlank()) {
            where.append(" AND (p.category_id = ? OR c.parent_id = ?)");
            args.add(categoryId);
            args.add(categoryId);
        }
        if (keyword != null && !keyword.isBlank()) {
            List<String> terms = new ArrayList<>();
            terms.add(keyword);
            terms.addAll(QueryTerms.of(keyword));
            List<String> uniqueTerms = QueryTerms.unique(terms, 10);
            if (!uniqueTerms.isEmpty()) {
                List<String> clauses = new ArrayList<>();
                for (String term : uniqueTerms) {
                    String like = "%" + term + "%";
                    clauses.add("p.name LIKE ?");
                    clauses.add("p.brand LIKE ?");
                    clauses.add("c.name LIKE ?");
                    clauses.add("p.tags_json LIKE ?");
                    clauses.add("p.selling_points_json LIKE ?");
                    for (int i = 0; i < 5; i++) {
                        args.add(like);
                    }
                }
                where.append(" AND (").append(String.join(" OR ", clauses)).append(')');
            }
        }
        return new Query(baseFrom + where, where.toString(), args);
    }

    private void insertProduct(ProductUpsertInput input) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO products (
                    product_id, merchant_id, name, brand, category_id, image_url, image_urls_json,
                    price, market_price, stock_quantity, stock_status, tags_json, selling_points_json,
                    recommend_reason, risk_notes_json, attributes_json, suitable_for_json,
                    not_suitable_for_json, description, status, sort_order, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', 100, ?, ?)
                """,
                input.productId(), input.merchantId(), input.name(), input.brand(),
                input.categoryId(), input.imageUrl(), json.write(List.of(input.imageUrl())),
                input.price(), input.marketPrice(), input.stockQuantity(), input.stockStatus(),
                json.write(input.tags()), json.write(input.sellingPoints()),
                input.recommendReason(), json.write(input.riskNotes()), "[]", "[]", "[]",
                input.description(), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private void upsertDefaultSku(ProductUpsertInput input) {
        String skuId = "sku_" + input.productId();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO product_skus (sku_id, product_id, sku_name, price, stock_quantity,
                                          stock_status, specs_json, is_default, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                ON DUPLICATE KEY UPDATE sku_name = VALUES(sku_name), price = VALUES(price),
                    stock_quantity = VALUES(stock_quantity), stock_status = VALUES(stock_status),
                    specs_json = VALUES(specs_json), is_default = TRUE, updated_at = VALUES(updated_at)
                """, skuId, input.productId(), input.name() + " 默认款", input.price(),
                input.stockQuantity(), input.stockStatus(),
                json.write(Map.of("版本", "默认款")),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private ProductCard mapCard(ResultSet rs) throws SQLException {
        return new ProductCard(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                json.stringList(rs.getString(13)), json.stringList(rs.getString(14)),
                rs.getString(15), json.stringList(rs.getString(16)));
    }

    private ProductDetail mapDetail(ResultSet rs) throws SQLException {
        List<ProductAttribute> attributes = json.attributes(rs.getString(19));
        return new ProductDetail(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                json.stringList(rs.getString(13)), json.stringList(rs.getString(14)),
                rs.getString(15), json.stringList(rs.getString(16)),
                json.stringList(rs.getString(17)), rs.getInt(18), attributes,
                json.stringList(rs.getString(20)), json.stringList(rs.getString(21)),
                rs.getString(22));
    }

    private Merchant mapMerchant(ResultSet rs) throws SQLException {
        return new Merchant(
                rs.getString("merchant_id"),
                rs.getString("name"),
                rs.getString("logo_url"),
                rs.getString("description"),
                nullToEmpty(rs.getString("service_phone")),
                rs.getString("status"));
    }

    private static List<Category> buildCategoryTree(List<Category> categories) {
        Map<String, Category> byId = new LinkedHashMap<>();
        for (Category category : categories) {
            byId.put(category.categoryId(), category);
        }
        List<String> rootIds = new ArrayList<>();
        for (Category category : categories) {
            if (category.parentId().isEmpty()) {
                rootIds.add(category.categoryId());
                continue;
            }
            Category parent = byId.get(category.parentId());
            if (parent == null) {
                rootIds.add(category.categoryId());
                continue;
            }
            parent.children().add(category);
        }
        List<Category> roots = new ArrayList<>(rootIds.size());
        for (String id : rootIds) {
            roots.add(byId.get(id));
        }
        return roots;
    }

    private static ProductUpsertInput normalize(ProductUpsertInput input) {
        return new ProductUpsertInput(
                input.productId(),
                input.merchantId(),
                input.name() == null ? "" : input.name(),
                blankTo(input.brand(), "未设置"),
                blankTo(input.categoryId(), "c_phone"),
                blankTo(input.imageUrl(),
                        "https://images.unsplash.com/photo-1516321318423-f06f85e504b3?auto=format&fit=crop&w=640&q=80"),
                blankTo(input.price(), "0.00"),
                blankTo(input.marketPrice(), blankTo(input.price(), "0.00")),
                input.stockQuantity() == null ? 0 : input.stockQuantity(),
                blankTo(input.stockStatus(), "in_stock"),
                input.tags() == null ? List.of() : input.tags(),
                input.sellingPoints() == null ? List.of() : input.sellingPoints(),
                input.recommendReason() == null ? "" : input.recommendReason(),
                input.riskNotes() == null ? List.of() : input.riskNotes(),
                input.description() == null ? "" : input.description());
    }

    private static ProductUpsertInput withProductId(ProductUpsertInput input, String productId) {
        return new ProductUpsertInput(productId, input.merchantId(), input.name(), input.brand(),
                input.categoryId(), input.imageUrl(), input.price(), input.marketPrice(),
                input.stockQuantity(), input.stockStatus(), input.tags(), input.sellingPoints(),
                input.recommendReason(), input.riskNotes(), input.description());
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? GoTime.zero() : value.toLocalDateTime();
    }

    private record Query(String fromAndWhere, String where, List<Object> args) {
    }
}
