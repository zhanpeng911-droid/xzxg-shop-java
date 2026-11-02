package com.xzxg.shop.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xzxg.shop.common.JsonCodec;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Java replacement for backend/cmd/import-products. Explicitly triggered with
 * {@code --shop.cli.import-products=true}; never runs on normal API startup.
 */
@Component
@ConditionalOnProperty(name = "shop.cli.import-products", havingValue = "true")
public class ProductImportCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductImportCommand.class);
    private static final String MERCHANT_ID = "m_dataset_001";
    private static final String ASSET_PREFIX = "/api/v1/assets/ecommerce_agent_dataset/";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonCodec json;
    private final ObjectMapper objectMapper;

    public ProductImportCommand(JdbcTemplate jdbc, TransactionTemplate transactions,
                                JsonCodec json, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.objectMapper = objectMapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatasetProduct(
            @JsonProperty("product_id") String productId,
            String title,
            String brand,
            String category,
            @JsonProperty("sub_category") String subCategory,
            @JsonProperty("base_price") BigDecimal basePrice,
            @JsonProperty("image_path") String imagePath,
            List<DatasetSku> skus,
            @JsonProperty("rag_knowledge") DatasetRag ragKnowledge) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatasetSku(
            @JsonProperty("sku_id") String skuId,
            Map<String, String> properties,
            BigDecimal price) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatasetRag(
            @JsonProperty("marketing_description") String marketingDescription,
            @JsonProperty("official_faq") List<DatasetFaq> officialFaq,
            @JsonProperty("user_reviews") List<DatasetUserReview> userReviews) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatasetFaq(String question, String answer) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatasetUserReview(String nickname, int rating, String content) {
    }

    public record ImportSummary(int products, int skus, int documents, int chunks) {
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            ImportSummary summary = importDataset(resolveDatasetRoot());
            log.info("dataset imported: products={}, skus={}, documents={}, chunks={}",
                    summary.products(), summary.skus(), summary.documents(), summary.chunks());
            System.exit(0);
        } catch (Exception ex) {
            log.error("import dataset failed", ex);
            System.exit(1);
        }
    }

    public ImportSummary importDataset(Path root) throws Exception {
        List<Path> files = datasetFiles(root);
        if (files.isEmpty()) {
            throw new IllegalStateException("no dataset product files found");
        }
        return transactions.execute(status -> {
            try {
                upsertMerchant();
                ImportSummary summary = new ImportSummary(0, 0, 0, 0);
                int productIndex = 0;
                for (Path file : files) {
                    DatasetProduct product = objectMapper.readValue(Files.readString(file),
                            DatasetProduct.class);
                    upsertCategory(product);
                    upsertProduct(product, productIndex++);
                    summary = new ImportSummary(summary.products() + 1, summary.skus(),
                            summary.documents(), summary.chunks());
                    List<DatasetSku> skus = product.skus() == null ? List.of() : product.skus();
                    for (int i = 0; i < skus.size(); i++) {
                        upsertSku(product, skus.get(i), i == 0);
                        summary = new ImportSummary(summary.products(), summary.skus() + 1,
                                summary.documents(), summary.chunks());
                    }
                    Knowledge knowledge = buildKnowledge(product);
                    for (DocumentRow document : knowledge.documents()) {
                        upsertDocument(document);
                        summary = new ImportSummary(summary.products(), summary.skus(),
                                summary.documents() + 1, summary.chunks());
                    }
                    int chunkIndex = 0;
                    for (ChunkRow chunk : knowledge.chunks()) {
                        upsertChunk(chunk, productIndex * 100 + chunkIndex++);
                        summary = new ImportSummary(summary.products(), summary.skus(),
                                summary.documents(), summary.chunks() + 1);
                    }
                }
                return summary;
            } catch (Exception ex) {
                status.setRollbackOnly();
                throw new IllegalStateException(ex);
            }
        });
    }

    private void upsertMerchant() {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO merchants (merchant_id, name, logo_url, description, service_phone,
                                       status, created_at, updated_at)
                VALUES (?, '真实商品数据集旗舰店', '/placeholder-merchant.svg',
                        '由 quality/data/ecommerce_agent_dataset 导入的比赛演示商品。',
                        '400-888-0000', 'active', ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description),
                    status = VALUES(status), updated_at = VALUES(updated_at)
                """, MERCHANT_ID, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private void upsertCategory(DatasetProduct product) {
        String parentId = categoryId(product.category());
        String childId = categoryId(product.category() + "_" + product.subCategory());
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO categories (category_id, parent_id, name, sort_order, created_at, updated_at)
                VALUES (?, '', ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), sort_order = VALUES(sort_order),
                    updated_at = VALUES(updated_at)
                """, parentId, product.category(), categorySort(product.category()),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbc.update("""
                INSERT INTO categories (category_id, parent_id, name, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE parent_id = VALUES(parent_id), name = VALUES(name),
                    updated_at = VALUES(updated_at)
                """, childId, parentId, product.subCategory(),
                categorySort(product.category()) + 10, Timestamp.valueOf(now),
                Timestamp.valueOf(now));
    }

    private void upsertProduct(DatasetProduct product, int index) {
        String imageUrl = ASSET_PREFIX + (product.imagePath() == null ? ""
                : product.imagePath().replaceFirst("^/+", ""));
        String categoryId = categoryId(product.category() + "_" + product.subCategory());
        List<String> sellingPoints = sellingPoints(product);
        String description = rag(product).marketingDescription() == null ? ""
                : rag(product).marketingDescription();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO products (
                    product_id, merchant_id, name, brand, category_id, image_url, image_urls_json,
                    price, market_price, stock_quantity, stock_status, tags_json,
                    selling_points_json, recommend_reason, risk_notes_json, attributes_json,
                    suitable_for_json, not_suitable_for_json, description, status, sort_order,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 100, 'in_stock', ?, ?, ?, '[]', ?, ?, '[]',
                          ?, 'active', ?, ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), brand = VALUES(brand),
                    category_id = VALUES(category_id), image_url = VALUES(image_url),
                    image_urls_json = VALUES(image_urls_json), price = VALUES(price),
                    market_price = VALUES(market_price), stock_quantity = VALUES(stock_quantity),
                    stock_status = VALUES(stock_status), tags_json = VALUES(tags_json),
                    selling_points_json = VALUES(selling_points_json),
                    recommend_reason = VALUES(recommend_reason), risk_notes_json = '[]',
                    attributes_json = VALUES(attributes_json),
                    suitable_for_json = VALUES(suitable_for_json),
                    not_suitable_for_json = '[]', description = VALUES(description),
                    status = 'active', sort_order = VALUES(sort_order), updated_at = VALUES(updated_at)
                """, product.productId(), MERCHANT_ID, product.title(), product.brand(),
                categoryId, imageUrl, json.write(List.of(imageUrl)), product.basePrice(),
                product.basePrice(),
                json.write(List.of(product.category(), product.subCategory(), product.brand())),
                json.write(sellingPoints), recommendReason(product),
                json.write(attributes(product)), json.write(suitableFor(product.category())),
                description, 1000 + index, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private void upsertSku(DatasetProduct product, DatasetSku sku, boolean isDefault) {
        Map<String, String> properties = sku.properties() == null ? Map.of() : sku.properties();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO product_skus (sku_id, product_id, sku_name, price, stock_quantity,
                                          stock_status, specs_json, is_default, created_at, updated_at)
                VALUES (?, ?, ?, ?, 100, 'in_stock', ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE sku_name = VALUES(sku_name), price = VALUES(price),
                    stock_quantity = VALUES(stock_quantity), stock_status = VALUES(stock_status),
                    specs_json = VALUES(specs_json), is_default = VALUES(is_default),
                    updated_at = VALUES(updated_at)
                """, sku.skuId(), product.productId(), skuName(product, sku), sku.price(),
                json.write(properties), isDefault, Timestamp.valueOf(now),
                Timestamp.valueOf(now));
    }

    private void upsertDocument(DocumentRow row) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO knowledge_documents (document_id, merchant_id, title, doc_type, content,
                                                 status, chunk_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'indexed', ?, ?, ?)
                ON DUPLICATE KEY UPDATE title = VALUES(title), doc_type = VALUES(doc_type),
                    content = VALUES(content), status = 'indexed',
                    chunk_count = VALUES(chunk_count), updated_at = VALUES(updated_at)
                """, row.documentId(), MERCHANT_ID, row.title(), row.docType(), row.content(),
                row.chunkCount(), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private void upsertChunk(ChunkRow row, int sortOrder) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO knowledge_chunks (chunk_id, title, snippet, source, sort_order,
                                              created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE title = VALUES(title), snippet = VALUES(snippet),
                    source = VALUES(source), sort_order = VALUES(sort_order),
                    updated_at = VALUES(updated_at)
                """, row.chunkId(), row.title(), row.snippet(), row.source(), sortOrder,
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private record DocumentRow(String documentId, String title, String docType, String content,
                               int chunkCount) {
    }

    private record ChunkRow(String chunkId, String title, String snippet, String source) {
    }

    private record Knowledge(List<DocumentRow> documents, List<ChunkRow> chunks) {
    }

    private Knowledge buildKnowledge(DatasetProduct product) {
        DatasetRag rag = rag(product);
        String title = product.title() == null ? "" : product.title();
        String marketing = rag.marketingDescription() == null ? "" : rag.marketingDescription();
        List<DocumentRow> documents = new ArrayList<>();
        List<ChunkRow> chunks = new ArrayList<>();
        documents.add(new DocumentRow(product.productId() + "_doc_marketing",
                title + " 商品说明", "product_detail", marketing,
                1 + (rag.officialFaq() == null ? 0 : rag.officialFaq().size())));
        chunks.add(new ChunkRow(product.productId() + "_ck_marketing", title + " 商品说明",
                marketing, product.productId()));
        List<DatasetFaq> faqs = rag.officialFaq() == null ? List.of() : rag.officialFaq();
        for (int i = 0; i < faqs.size(); i++) {
            DatasetFaq faq = faqs.get(i);
            String suffix = String.format("%02d", i + 1);
            documents.add(new DocumentRow(product.productId() + "_doc_faq_" + suffix,
                    faq.question(), "faq", faq.answer(), 1));
            chunks.add(new ChunkRow(product.productId() + "_ck_faq_" + suffix,
                    faq.question(), faq.answer(), product.productId()));
        }
        List<DatasetUserReview> reviews = rag.userReviews() == null ? List.of() : rag.userReviews();
        for (int i = 0; i < reviews.size(); i++) {
            DatasetUserReview review = reviews.get(i);
            String suffix = String.format("%02d", i + 1);
            chunks.add(new ChunkRow(product.productId() + "_ck_review_" + suffix,
                    title + " 用户评价",
                    review.nickname() + "，评分 " + review.rating() + "：" + review.content(),
                    product.productId()));
        }
        return new Knowledge(documents, chunks);
    }

    private static DatasetRag rag(DatasetProduct product) {
        return product.ragKnowledge() == null
                ? new DatasetRag("", List.of(), List.of())
                : product.ragKnowledge();
    }

    private static List<String> sellingPoints(DatasetProduct product) {
        List<String> points = new ArrayList<>();
        points.add(product.subCategory());
        points.add(product.brand());
        for (DatasetSku sku : product.skus() == null ? List.<DatasetSku>of() : product.skus()) {
            Map<String, String> properties = sku.properties() == null ? Map.of() : sku.properties();
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                points.add(entry.getKey() + "：" + entry.getValue());
            }
            if (points.size() >= 5) {
                break;
            }
        }
        return compact(points, 5);
    }

    private static List<String> compact(List<String> input, int limit) {
        List<String> output = new ArrayList<>();
        for (String item : input) {
            String trimmed = item == null ? "" : item.trim();
            if (trimmed.isEmpty() || output.contains(trimmed)) {
                continue;
            }
            output.add(trimmed);
            if (output.size() == limit) {
                break;
            }
        }
        return output;
    }

    private static String recommendReason(DatasetProduct product) {
        String text = rag(product).marketingDescription() == null
                ? "" : rag(product).marketingDescription();
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length <= 120) {
            return text;
        }
        return new String(codePoints, 0, 120) + "...";
    }

    private static List<Map<String, String>> attributes(DatasetProduct product) {
        List<Map<String, String>> items = new ArrayList<>();
        items.add(Map.of("key", "品类", "value", product.category()));
        items.add(Map.of("key", "子品类", "value", product.subCategory()));
        items.add(Map.of("key", "品牌", "value", product.brand()));
        return items;
    }

    private static List<String> suitableFor(String category) {
        return switch (category) {
            case "美妆护肤" -> List.of("护肤", "成分功效咨询", "规格选择");
            case "数码电子" -> List.of("参数对比", "预算决策", "性能咨询");
            case "服饰运动" -> List.of("尺码选择", "穿搭场景", "运动需求");
            case "食品饮料" -> List.of("家庭囤货", "口味选择", "生活场景");
            case "美妆个护" -> List.of("彩妆护肤", "香型选择", "个人护理");
            case "食品生鲜" -> List.of("日常生鲜", "家庭囤货", "口味选择");
            case "宠物生活" -> List.of("宠物喂养", "口粮选择", "日常补货");
            case "家居家装" -> List.of("空间搭配", "尺寸选择", "材质比较");
            case "厨房餐具" -> List.of("厨房收纳", "烹饪场景", "规格比较");
            case "电脑办公" -> List.of("办公学习", "参数对比", "预算决策");
            case "手机数码" -> List.of("换机咨询", "配件搭配", "参数对比");
            case "服饰鞋包" -> List.of("尺码选择", "穿搭场景", "风格比较");
            case "钟表配饰" -> List.of("礼赠咨询", "风格搭配", "材质比较");
            case "运动户外" -> List.of("运动场景", "装备搭配", "耐用性咨询");
            case "汽车摩托" -> List.of("出行装备", "配件选择", "预算比较");
            default -> List.of("日常购物咨询");
        };
    }

    private static String skuName(DatasetProduct product, DatasetSku sku) {
        Map<String, String> properties = sku.properties() == null ? Map.of() : sku.properties();
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            values.add(entry.getKey() + entry.getValue());
        }
        values.sort(String::compareTo);
        if (values.isEmpty()) {
            return product.title() + " 默认款";
        }
        return product.title() + " " + String.join(" ", values);
    }

    private static String categoryId(String name) {
        String mapped = CATEGORY_IDS.get(name);
        if (mapped != null) {
            return mapped;
        }
        String[] parts = name.split("_");
        if (parts.length == 2) {
            String parent = CATEGORY_IDS.get(parts[0]);
            String child = CATEGORY_IDS.get(parts[1]);
            if (parent != null && child != null) {
                return parent + "_" + child.replaceFirst("^c_dataset_", "");
            }
        }
        return "c_dataset_" + sanitizeId(name);
    }

    private static String sanitizeId(String value) {
        StringBuilder builder = new StringBuilder();
        for (char ch : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_') {
                builder.append(ch);
            }
        }
        return builder.isEmpty() ? "cat_" + value.codePointCount(0, value.length())
                : builder.toString();
    }

    private static int categorySort(String category) {
        return switch (category) {
            case "美妆护肤" -> 100;
            case "数码电子" -> 200;
            case "服饰运动" -> 300;
            case "食品饮料" -> 400;
            case "美妆个护" -> 500;
            case "食品生鲜" -> 600;
            case "宠物生活" -> 700;
            case "家居家装" -> 800;
            case "厨房餐具" -> 900;
            case "电脑办公" -> 1000;
            case "手机数码" -> 1100;
            case "服饰鞋包" -> 1200;
            case "钟表配饰" -> 1300;
            case "运动户外" -> 1400;
            case "汽车摩托" -> 1500;
            default -> 9900;
        };
    }

    private static List<Path> datasetFiles(Path root) throws Exception {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> path.getParent() != null
                            && "data".equals(path.getParent().getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static Path resolveDatasetRoot() {
        String configured = System.getenv("ECOMMERCE_DATASET_ROOT");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        Path root = Paths.get("quality", "data", "ecommerce_agent_dataset").toAbsolutePath().normalize();
        if (Files.isDirectory(root)) {
            return root;
        }
        return Paths.get("..", "quality", "data", "ecommerce_agent_dataset")
                .toAbsolutePath().normalize();
    }

    private static final Map<String, String> CATEGORY_IDS = categoryIds();

    private static Map<String, String> categoryIds() {
        Map<String, String> map = new HashMap<>();
        map.put("美妆护肤", "c_dataset_beauty");
        map.put("数码电子", "c_dataset_digital");
        map.put("服饰运动", "c_dataset_clothes");
        map.put("食品饮料", "c_dataset_food");
        map.put("精华", "c_dataset_serum");
        map.put("面霜", "c_dataset_cream");
        map.put("防晒", "c_dataset_sunscreen");
        map.put("洁面", "c_dataset_cleanser");
        map.put("面膜", "c_dataset_mask");
        map.put("眼霜", "c_dataset_eye_cream");
        map.put("化妆水", "c_dataset_toner");
        map.put("卸妆", "c_dataset_makeup_remover");
        map.put("唇釉", "c_dataset_lip_glaze");
        map.put("眉笔", "c_dataset_eyebrow_pencil");
        map.put("粉底液", "c_dataset_foundation");
        map.put("蜜粉", "c_dataset_powder");
        map.put("智能手机", "c_dataset_smartphone");
        map.put("笔记本电脑", "c_dataset_laptop");
        map.put("平板电脑", "c_dataset_tablet");
        map.put("真无线耳机", "c_dataset_tws");
        map.put("跑步鞋", "c_dataset_running_shoes");
        map.put("篮球鞋", "c_dataset_basketball_shoes");
        map.put("瑜伽裤", "c_dataset_yoga_pants");
        map.put("速干T恤", "c_dataset_quickdry_tshirt");
        map.put("短袖T恤", "c_dataset_tshirt");
        map.put("运动短裤", "c_dataset_sports_shorts");
        map.put("运动长裤", "c_dataset_sports_pants");
        map.put("卫衣", "c_dataset_hoodie");
        map.put("户外裤", "c_dataset_outdoor_pants");
        map.put("徒步鞋", "c_dataset_hiking_shoes");
        map.put("背包", "c_dataset_backpack");
        map.put("帽子", "c_dataset_hat");
        map.put("功能饮料", "c_dataset_energy_drink");
        map.put("坚果/零食", "c_dataset_snacks");
        map.put("咖啡", "c_dataset_coffee");
        map.put("方便食品", "c_dataset_convenience_food");
        map.put("牛奶", "c_dataset_milk");
        map.put("碳酸饮料", "c_dataset_soda");
        map.put("茶饮", "c_dataset_tea");
        map.put("调味品", "c_dataset_condiment");
        map.put("酸奶", "c_dataset_yogurt");
        map.put("美妆个护", "c_dataset_beauty_personal_care");
        map.put("食品生鲜", "c_dataset_fresh_food");
        map.put("宠物生活", "c_dataset_pet_life");
        map.put("家居家装", "c_dataset_home_living");
        map.put("厨房餐具", "c_dataset_kitchen_dining");
        map.put("电脑办公", "c_dataset_computer_office");
        map.put("手机数码", "c_dataset_mobile_digital");
        map.put("服饰鞋包", "c_dataset_fashion_bags");
        map.put("钟表配饰", "c_dataset_watch_accessories");
        map.put("运动户外", "c_dataset_sports_outdoor");
        map.put("汽车摩托", "c_dataset_auto_moto");
        map.put("彩妆", "c_dataset_makeup");
        map.put("香水", "c_dataset_fragrance");
        map.put("身体护理", "c_dataset_body_care");
        map.put("水果蔬菜", "c_dataset_fruit_veg");
        map.put("肉禽蛋奶", "c_dataset_meat_egg_dairy");
        map.put("粮油冲调", "c_dataset_grain_oil");
        map.put("饮品零食", "c_dataset_drinks_snacks");
        map.put("宠物食品", "c_dataset_pet_food");
        map.put("纸品清洁", "c_dataset_paper_cleaning");
        map.put("家具", "c_dataset_furniture");
        map.put("家居装饰", "c_dataset_home_decor");
        map.put("厨房配件", "c_dataset_kitchen_accessories");
        map.put("耳机音箱", "c_dataset_audio");
        map.put("充电配件", "c_dataset_charging_accessories");
        map.put("智能穿戴", "c_dataset_wearables");
        map.put("拍摄配件", "c_dataset_photo_accessories");
        map.put("男装", "c_dataset_menswear");
        map.put("男鞋", "c_dataset_mens_shoes");
        map.put("女装", "c_dataset_womenswear");
        map.put("女鞋", "c_dataset_womens_shoes");
        map.put("箱包", "c_dataset_bags");
        map.put("男士腕表", "c_dataset_mens_watches");
        map.put("女士腕表", "c_dataset_womens_watches");
        map.put("首饰", "c_dataset_jewellery");
        map.put("眼镜", "c_dataset_eyewear");
        map.put("球类运动", "c_dataset_ball_sports");
        map.put("摩托车", "c_dataset_motorcycle");
        map.put("汽车", "c_dataset_vehicle");
        return Map.copyOf(map);
    }
}
