package com.xzxg.shop.eval;

import com.xzxg.shop.catalog.CatalogService;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.domain.Citation;
import com.xzxg.shop.domain.ProductCard;
import com.xzxg.shop.image.ImageSearchService;
import com.xzxg.shop.knowledge.KnowledgeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class EvalService {

    private final KnowledgeService knowledge;
    private final CatalogService catalog;
    private final ImageSearchService images;
    private final ObjectMapper objectMapper;

    public EvalService(KnowledgeService knowledge, CatalogService catalog,
                       ImageSearchService images, ObjectMapper objectMapper) {
        this.knowledge = knowledge;
        this.catalog = catalog;
        this.images = images;
        this.objectMapper = objectMapper;
    }

    public Map<String, String> classify(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        boolean nonGuide = lower.contains("购物车") || lower.contains("加购")
                || lower.contains("下单") || lower.contains("结算") || lower.contains("订单")
                || lower.contains("支付") || lower.contains("取消") || lower.contains("收货")
                || lower.contains("优惠券") || lower.contains("促销") || lower.contains("活动")
                || lower.contains("评价");
        String route = nonGuide ? "non_guide" : "guide";
        String intent;
        String level = "P1";
        String secondary = "";
        if (nonGuide) {
            if (lower.contains("订单") || lower.contains("支付") || lower.contains("取消")
                    || lower.contains("收货")) {
                intent = "order_service";
                secondary = "order_manage";
            } else if (lower.contains("优惠券") || lower.contains("券")) {
                intent = "coupon_service";
                secondary = "coupon_query";
            } else if (lower.contains("促销") || lower.contains("活动")) {
                intent = "promotion_service";
                secondary = "promotion_query";
            } else if (lower.contains("评价")) {
                intent = "review_service";
                secondary = "review_manage";
            } else {
                intent = "cart_service";
                secondary = "cart_manage";
            }
        } else if (lower.contains("对比") || lower.contains("比较")) {
            intent = "product_compare";
            secondary = "comparison";
        } else if (lower.contains("怎么") || lower.contains("为什么") || lower.contains("是什么")) {
            intent = "knowledge_qa";
            secondary = "knowledge";
        } else {
            intent = "product_recommend";
            secondary = "recommendation";
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("route", route);
        result.put("intent", intent);
        result.put("level", level);
        result.put("secondary_level", secondary);
        return result;
    }

    public Map<String, Object> rag(String keyword, int topK) {
        int effective = topK <= 0 || topK > 20 ? 5 : topK;
        List<Citation> items = knowledge.search(keyword, effective);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> products(String query, int limit) {
        long startedAt = System.nanoTime();
        int effective = limit <= 0 || limit > 20 ? 10 : limit;
        PageResponse<ProductCard> page = catalog.products(query, null,
                PageRequest.of(1, effective));
        List<String> ids = page.items().stream().map(ProductCard::productId).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("product_ids", ids);
        result.put("candidate_product_ids", ids);
        result.put("dropped_product_ids", List.of());
        result.put("relevance_status", ids.isEmpty() ? "no_match" : "ok");
        result.put("relevance_reason", ids.isEmpty() ? "没有匹配商品" : "关键词召回命中");
        result.put("duration_ms", (System.nanoTime() - startedAt) / 1_000_000);
        result.put("items", page.items());
        return result;
    }

    public ImageSearchService.ImageSearchResponse image(String fileId, String objectKey,
                                                        String imageUrl, int topK) {
        return images.search(fileId, objectKey, imageUrl, topK);
    }

    public Map<String, Object> dashboard() {
        List<Map<String, Object>> datasets = datasets();
        List<Map<String, Object>> reports = reports();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", toolSuites(datasets, reports));
        result.put("datasets", datasets);
        result.put("reports", reports);
        return result;
    }

    public JsonNode report(String runId, String fileName) {
        Path root = reportRoot();
        Path target = root.resolve(runId).resolve(fileName).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("report not found");
        }
        try {
            return objectMapper.readTree(Files.readString(target));
        } catch (Exception ex) {
            throw new IllegalArgumentException("report unreadable");
        }
    }

    private List<Map<String, Object>> datasets() {
        Path root = dataRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .forEach(path -> {
                        String file = path.getFileName().toString();
                        String id = file.substring(0, file.length() - ".jsonl".length());
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", id);
                        item.put("type", datasetType(id));
                        item.put("name", datasetName(id));
                        item.put("path", path.toAbsolutePath().toString().replace('\\', '/'));
                        item.put("case_count", countLines(path));
                        item.put("updated_at", updatedAt(path));
                        items.add(item);
                    });
        } catch (Exception ignored) {
            return List.of();
        }
        items.sort(Comparator.comparing(item -> String.valueOf(item.get("id"))));
        return items;
    }

    private List<Map<String, Object>> reports() {
        Path root = reportRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        try (Stream<Path> runs = Files.list(root)) {
            runs.filter(Files::isDirectory).forEach(run -> {
                try (Stream<Path> files = Files.list(run)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .forEach(path -> {
                                try {
                                    JsonNode node = objectMapper.readTree(Files.readString(path));
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("id", run.getFileName() + "/" + path.getFileName());
                                    item.put("run_id", run.getFileName().toString());
                                    item.put("file_name", path.getFileName().toString());
                                    item.put("type", node.path("type").asString(""));
                                    item.put("dataset", node.path("dataset").asString(""));
                                    item.put("generated_at", node.path("generated_at").asString(""));
                                    item.put("total_cases", node.path("total_cases").asInt(0));
                                    item.put("accuracy", node.path("accuracy").asDouble(0));
                                    item.put("pass_rate", node.path("pass_rate").asDouble(0));
                                    items.add(item);
                                } catch (Exception ignored) {
                                    // skip unreadable report
                                }
                            });
                } catch (Exception ignored) {
                    // skip unreadable run directory
                }
            });
        } catch (Exception ignored) {
            return List.of();
        }
        items.sort(Comparator.comparing(item -> String.valueOf(item.get("generated_at")),
                Comparator.reverseOrder()));
        return items.size() > 50 ? items.subList(0, 50) : items;
    }

    private static List<Map<String, Object>> toolSuites(List<Map<String, Object>> datasets,
                                                        List<Map<String, Object>> reports) {
        String[][] suites = {
                {"rag_retriever_eval", "RAG 检索召回", "tool", "rag_recall_cases"},
                {"product_search_eval", "商品召回重排", "tool", "rag_recall_cases"},
                {"image_search_eval", "图片搜索", "tool", "image_search_cases"},
                {"intent", "意图识别", "tool", "intent_cases"},
                {"non_guide_intent", "非导购服务域识别", "tool", "non_guide_intent_cases"},
                {"agent_e2e", "导购 Agent 端到端", "agent", "agent_e2e_queries"},
                {"agent_no_inventory", "Agent 无库存误挂品", "agent", "agent_no_inventory_cases"},
        };
        List<String> datasetIds = datasets.stream()
                .map(item -> String.valueOf(item.get("id"))).toList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (String[] suite : suites) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", suite[0]);
            item.put("name", suite[1]);
            item.put("scope", suite[2]);
            item.put("dataset_id", datasetIds.contains(suite[3]) ? suite[3] : "");
            item.put("latest_report_id", "");
            items.add(item);
        }
        return items;
    }

    private static String datasetType(String id) {
        if (id.contains("no_inventory")) {
            return "agent_no_inventory";
        }
        if (id.contains("rag")) {
            return "rag_recall";
        }
        if (id.contains("intent")) {
            return "intent";
        }
        if (id.contains("image_search")) {
            return "image_search_eval";
        }
        if (id.contains("agent")) {
            return "agent_e2e";
        }
        return "custom";
    }

    private static String datasetName(String id) {
        return id.replace('_', ' ');
    }

    private static int countLines(Path path) {
        try (Stream<String> lines = Files.lines(path)) {
            return (int) lines.filter(line -> !line.isBlank()).count();
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String updatedAt(Path path) {
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()),
                    ZoneId.systemDefault()).toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private static Path dataRoot() {
        String configured = System.getenv("EVAL_DATA_DIR");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        Path root = Paths.get("quality", "data", "eval").toAbsolutePath().normalize();
        return Files.isDirectory(root) ? root
                : Paths.get("..", "quality", "data", "eval").toAbsolutePath().normalize();
    }

    private static Path reportRoot() {
        String configured = System.getenv("EVAL_REPORT_DIR");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        Path root = Paths.get("quality", "reports").toAbsolutePath().normalize();
        return Files.isDirectory(root) ? root
                : Paths.get("..", "quality", "reports").toAbsolutePath().normalize();
    }
}
