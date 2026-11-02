package com.xzxg.shop.retrieval;

import com.xzxg.shop.common.JsonCodec;
import com.xzxg.shop.config.ShopProperties;
import com.xzxg.shop.domain.ProductAttribute;
import com.xzxg.shop.image.ImageEmbeddingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Explicit Milvus bootstrap: creates collections, embeds MySQL rows, upserts, and
 * loads. Runs only when vector.enabled=true; failures are logged and keyword
 * retrieval keeps working.
 */
@Component
public class VectorBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorBootstrapService.class);

    private final ShopProperties.Vector vectorConfig;
    private final ShopProperties.Embedding embeddingConfig;
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final EmbeddingClient embeddings;
    private final ImageEmbeddingService imageEmbeddings;
    private final MilvusClient milvus;

    public VectorBootstrapService(ShopProperties properties, JdbcTemplate jdbc, JsonCodec json,
                                  EmbeddingClient embeddings,
                                  ImageEmbeddingService imageEmbeddings, MilvusClient milvus) {
        this.vectorConfig = properties.getRetrieval().getVector();
        this.embeddingConfig = properties.getRetrieval().getEmbedding();
        this.jdbc = jdbc;
        this.json = json;
        this.embeddings = embeddings;
        this.imageEmbeddings = imageEmbeddings;
        this.milvus = milvus;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!milvus.enabled() || !embeddings.configured()) {
            return;
        }
        Thread worker = new Thread(this::bootstrap, "vector-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    public void bootstrap() {
        try {
            boolean productsReady = milvus.collectionHasRows(vectorConfig.getProductCollection());
            boolean knowledgeReady = milvus.collectionHasRows(vectorConfig.getKnowledgeCollection());
            if (productsReady && knowledgeReady) {
                milvus.loadCollection(vectorConfig.getProductCollection());
                milvus.loadCollection(vectorConfig.getKnowledgeCollection());
                bootstrapImages();
                return;
            }
            float[] sample = embeddings.embed(List.of("xzxg vector bootstrap")).get(0);
            int dimension = sample.length;
            milvus.ensureCollection(vectorConfig.getProductCollection(), "product_id", dimension);
            milvus.ensureCollection(vectorConfig.getKnowledgeCollection(), "chunk_id", dimension);
            upsertText(vectorConfig.getProductCollection(), productRows());
            upsertText(vectorConfig.getKnowledgeCollection(), knowledgeRows());
            milvus.loadCollection(vectorConfig.getProductCollection());
            milvus.loadCollection(vectorConfig.getKnowledgeCollection());
            log.info("vector index ready: products/knowledge dimension={}", dimension);
            bootstrapImages();
        } catch (Exception ex) {
            log.warn("vector bootstrap failed, keyword retrieval remains active: {}",
                    ex.getMessage());
        }
    }

    private void upsertText(String collection, List<Map<String, Object>> rows) throws Exception {
        if (rows.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, Math.min(embeddingConfig.getBatchSize(), 10));
        for (int start = 0; start < rows.size(); start += batchSize) {
            int end = Math.min(start + batchSize, rows.size());
            List<String> texts = new ArrayList<>();
            for (int i = start; i < end; i++) {
                texts.add(String.valueOf(rows.get(i).get("search_text")));
            }
            List<float[]> vectors = embeddings.embed(texts);
            List<Map<String, Object>> entities = new ArrayList<>();
            for (int i = 0; i < vectors.size(); i++) {
                Map<String, Object> entity = new LinkedHashMap<>(rows.get(start + i));
                entity.remove("search_text");
                entity.put("embedding", vectors.get(i));
                entities.add(entity);
            }
            milvus.upsert(collection, entities);
        }
    }

    private List<Map<String, Object>> productRows() {
        return jdbc.query("""
                SELECT p.product_id, p.merchant_id, p.category_id, p.brand, p.stock_status,
                       p.price, p.name, p.tags_json, p.selling_points_json, p.recommend_reason,
                       p.description, p.attributes_json, p.suitable_for_json
                FROM products p
                JOIN merchants m ON m.merchant_id = p.merchant_id
                WHERE p.status = 'active' AND m.status = 'active'
                ORDER BY p.product_id
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_id", rs.getString("product_id"));
            row.put("merchant_id", rs.getString("merchant_id"));
            row.put("category_id", rs.getString("category_id"));
            row.put("brand", rs.getString("brand"));
            row.put("status", "active");
            row.put("stock_status", rs.getString("stock_status"));
            row.put("price", parseDouble(rs.getString("price")));
            row.put("updated_at_ts", Instant.now().getEpochSecond());
            row.put("search_text", productSearchText(rs));
            return row;
        });
    }

    private String productSearchText(ResultSet rs) throws SQLException {
        List<ProductAttribute> attributes = json.attributes(rs.getString("attributes_json"));
        List<String> attributeText = new ArrayList<>();
        for (ProductAttribute attribute : attributes) {
            attributeText.add(attribute.key() + "：" + attribute.value());
        }
        return String.join("\n",
                nullToEmpty(rs.getString("name")),
                nullToEmpty(rs.getString("brand")),
                nullToEmpty(rs.getString("category_id")),
                String.join(" ", json.stringList(rs.getString("tags_json"))),
                String.join(" ", json.stringList(rs.getString("selling_points_json"))),
                String.join(" ", attributeText),
                String.join(" ", json.stringList(rs.getString("suitable_for_json"))),
                nullToEmpty(rs.getString("recommend_reason")),
                nullToEmpty(rs.getString("description")));
    }

    private List<Map<String, Object>> knowledgeRows() {
        return jdbc.query("""
                SELECT chunk_id, title, snippet, source, sort_order
                FROM knowledge_chunks
                ORDER BY sort_order, chunk_id
                """, (rs, rowNum) -> {
            String chunkId = rs.getString("chunk_id");
            String source = nullToEmpty(rs.getString("source"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunk_id", chunkId);
            row.put("source", source);
            row.put("source_type", sourceType(source));
            row.put("doc_type", docType(chunkId));
            row.put("quality_score", 0.8);
            row.put("created_at_ts", Instant.now().getEpochSecond());
            row.put("search_text", nullToEmpty(rs.getString("title")) + "\n"
                    + nullToEmpty(rs.getString("snippet")));
            if (source.startsWith("p_")) {
                jdbc.query("""
                        SELECT product_id, merchant_id, category_id, brand
                        FROM products WHERE product_id = ?
                        """, product -> {
                    row.put("product_id", product.getString("product_id"));
                    row.put("merchant_id", product.getString("merchant_id"));
                    row.put("category_id", product.getString("category_id"));
                    row.put("brand", product.getString("brand"));
                }, source);
            }
            return row;
        });
    }

    private void bootstrapImages() {
        try {
            if (milvus.collectionHasRows(vectorConfig.getImageCollection())) {
                return;
            }
            List<ImageTask> tasks = new ArrayList<>();
            jdbc.query("""
                    SELECT product_id, merchant_id, category_id, brand, image_url,
                           image_urls_json
                    FROM products p
                    JOIN merchants m ON m.merchant_id = p.merchant_id
                    WHERE p.status = 'active' AND m.status = 'active'
                    ORDER BY p.product_id
                    """, rs -> {
                List<String> urls = json.stringList(rs.getString("image_urls_json"));
                if (urls.isEmpty() && !nullToEmpty(rs.getString("image_url")).isEmpty()) {
                    urls = List.of(rs.getString("image_url"));
                }
                for (int index = 0; index < urls.size(); index++) {
                    tasks.add(new ImageTask("img_" + rs.getString("product_id") + "_" + index,
                            rs.getString("product_id"), rs.getString("merchant_id"),
                            rs.getString("category_id"), rs.getString("brand"), urls.get(index),
                            index == 0 ? "main" : "detail"));
                }
            });
            List<Map<String, Object>> rows = new ArrayList<>();
            int dimension = 0;
            for (ImageTask task : tasks) {
                try {
                    byte[] data = readImage(task.source());
                    float[] vector = imageEmbeddings.embed(data);
                    if (vector.length == 0) {
                        continue;
                    }
                    dimension = vector.length;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("image_vector_id", task.vectorId());
                    row.put("product_id", task.productId());
                    row.put("merchant_id", task.merchantId());
                    row.put("category_id", task.categoryId());
                    row.put("brand", task.brand());
                    row.put("image_url", task.source());
                    row.put("image_type", task.imageType());
                    row.put("quality_score", 0.8);
                    row.put("embedding", vector);
                    rows.add(row);
                } catch (Exception ignored) {
                    // skip unreadable image, matching Go's per-image failure handling
                }
            }
            if (rows.isEmpty() || dimension == 0) {
                return;
            }
            milvus.ensureCollection(vectorConfig.getImageCollection(), "image_vector_id",
                    dimension);
            milvus.upsert(vectorConfig.getImageCollection(), rows);
            milvus.loadCollection(vectorConfig.getImageCollection());
            log.info("image vector index ready: rows={} dimension={}", rows.size(), dimension);
        } catch (Exception ex) {
            log.warn("image vector bootstrap failed: {}", ex.getMessage());
        }
    }

    private static byte[] readImage(String source) throws Exception {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(source))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build();
            java.net.http.HttpResponse<byte[]> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            return response.body();
        }
        String path = source;
        if (path.startsWith("/api/v1/assets/ecommerce_agent_dataset/")) {
            path = Paths.get("..", "quality", "data", "ecommerce_agent_dataset",
                    path.substring("/api/v1/assets/ecommerce_agent_dataset/".length()))
                    .toString();
        }
        Path file = Paths.get(path);
        if (!Files.exists(file) && !file.isAbsolute()) {
            file = Paths.get("..", path);
        }
        return Files.readAllBytes(file);
    }

    private static String sourceType(String source) {
        if (source.startsWith("p_")) {
            return "product";
        }
        if (source.startsWith("doc_")) {
            return "merchant_doc";
        }
        return "platform_doc";
    }

    private static String docType(String chunkId) {
        if (chunkId.contains("_faq_")) {
            return "faq";
        }
        if (chunkId.contains("_review_")) {
            return "review";
        }
        if (chunkId.contains("_marketing")) {
            return "product_desc";
        }
        return "document";
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value == null ? "0" : value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ImageTask(String vectorId, String productId, String merchantId,
                             String categoryId, String brand, String source, String imageType) {
    }
}
