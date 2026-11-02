package com.xzxg.shop.agent;

import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.domain.ProductCard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Rule-first guide suggestions with an optional model refinement. */
@Service
public class GuideSuggestionService {

    public record Item(String id, String question, String reason) {
    }

    private final LlmClient llm;
    private final PromptRepository prompts;
    private final ObjectMapper objectMapper;

    public GuideSuggestionService(LlmClient llm, PromptRepository prompts,
                                  ObjectMapper objectMapper) {
        this.llm = llm;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    public List<Item> build(String page, Map<String, Object> context, int limit) {
        int effectiveLimit = limit <= 0 || limit > 3 ? 3 : limit;
        List<Item> candidates = switch (page == null ? "" : page.trim()) {
            case "products" -> productPage(context);
            case "cart" -> cartPage(context);
            case "orders" -> orderPage(context);
            case "product_detail" -> productDetail(context);
            default -> throw new IllegalArgumentException("bad_page");
        };
        List<Item> items = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Item item : candidates) {
            String question = compact(item.question(), 10);
            if (item.id().isEmpty() || question.isEmpty() || seen.contains(question)) {
                continue;
            }
            seen.add(question);
            items.add(new Item(item.id(), question, item.reason()));
            if (items.size() >= effectiveLimit) {
                break;
            }
        }
        return items;
    }

    public List<Item> refine(String page, Map<String, Object> context, int limit,
                             List<Item> fallback) {
        if (!llm.enabled() || fallback.isEmpty()) {
            return fallback;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("page", page);
            payload.put("context", context == null ? Map.of() : context);
            payload.put("limit", limit);
            payload.put("candidates", fallback);
            String system = prompts.active("agent.prompt.guide_suggestions")
                    .map(value -> value.content())
                    .orElse("根据页面上下文生成最多三条中文导购建议，只输出 JSON："
                            + "{\"items\":[{\"id\":\"\",\"question\":\"\",\"reason\":\"\"}]}。");
            String response = llm.complete(llm.smallModel(), List.of(
                    new LlmClient.Message("system", system),
                    new LlmClient.Message("user", objectMapper.writeValueAsString(payload))), 0.2);
            JsonNode items = objectMapper.readTree(stripFence(response)).path("items");
            List<Item> refined = new ArrayList<>();
            for (JsonNode node : items) {
                String question = compact(node.path("question").asString(""), 10);
                if (question.isEmpty()) {
                    continue;
                }
                refined.add(new Item(node.path("id").asString("model_" + (refined.size() + 1)),
                        question, node.path("reason").asString("")));
                if (refined.size() >= 3) {
                    break;
                }
            }
            return refined.isEmpty() ? fallback : refined;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static List<Item> productPage(Map<String, Object> context) {
        String category = shortTerm(string(context, "category_name"), 8);
        String keyword = shortTerm(string(context, "keyword"), 8);
        List<Item> items = new ArrayList<>();
        if (!keyword.isEmpty()) {
            items.add(new Item("keyword_recommend", "帮我选" + keyword,
                    "根据当前搜索词生成可直接发送给导购的选购问题"));
        }
        if (!category.isEmpty()) {
            items.add(new Item("category_budget", category + "怎么选？",
                    "根据当前分类引导用户补充预算和偏好"));
        }
        items.add(new Item("compare_visible", "比较当前商品", "用户可能正在浏览多个候选商品"));
        items.add(new Item("budget_recommend", "高性价比推荐", "泛选场景下给用户一个低门槛入口"));
        return items;
    }

    private static List<Item> cartPage(Map<String, Object> context) {
        int count = integer(context, "cart_item_count");
        List<Item> items = new ArrayList<>();
        if (count > 0) {
            items.add(new Item("cart_analyze", "帮我分析这" + count + "件商品",
                    "购物车内已有商品，适合做购买决策和优惠分析"));
        }
        items.add(new Item("cart_worth", "购物车哪些值得买？", "引导用户筛选购物车里的高价值商品"));
        items.add(new Item("cart_checkout", "现在适合直接下单吗？", "帮助用户确认优惠、库存和下单时机"));
        items.add(new Item("cart_coupon", "看看能省多少", "购物车页面常见诉求是优惠试算"));
        return items;
    }

    private static List<Item> orderPage(Map<String, Object> context) {
        List<Item> items = new ArrayList<>();
        if (integer(context, "pending_payment_count") > 0) {
            items.add(new Item("pending_payment", "哪些订单还没支付？", "当前存在待支付订单"));
        }
        if (integer(context, "pending_review_count") > 0) {
            items.add(new Item("pending_review", "待评价怎么写？", "当前存在可评价订单"));
        }
        items.add(new Item("order_summary", "总结订单状态", "订单页适合聚合物流、支付和售后状态"));
        items.add(new Item("order_action", "哪些订单待办？", "帮助用户快速定位待办订单"));
        return items;
    }

    private static List<Item> productDetail(Map<String, Object> context) {
        String name = shortTerm(string(context, "product_name"), 10);
        List<Item> items = new ArrayList<>();
        if (!name.isEmpty()) {
            items.add(new Item("product_worth", name + "值得买吗？", "商品详情页最常见的决策问题"));
        }
        items.add(new Item("product_fit", "这个商品适合我吗？", "引导用户结合自身场景判断适配性"));
        items.add(new Item("product_risk", "这个商品有什么缺点？", "帮助用户了解风险和避坑点"));
        items.add(new Item("product_compare", "同类里它值得买吗？", "详情页常见横向对比诉求"));
        return items;
    }

    private static String string(Map<String, Object> context, String key) {
        Object value = context == null ? null : context.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static int integer(Map<String, Object> context, String key) {
        Object value = context == null ? null : context.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String shortTerm(String value, int maxRunes) {
        String joined = value == null ? "" : value.replaceAll("\\s+", "");
        int[] codePoints = joined.codePoints().toArray();
        return codePoints.length <= maxRunes ? joined
                : new String(codePoints, 0, maxRunes);
    }

    private static String compact(String question, int maxRunes) {
        String trimmed = question == null ? "" : question.trim()
                .replaceAll("^[\\s　。.!！?？]+", "")
                .replaceAll("[\\s　。.!！?？]+$", "");
        int[] codePoints = trimmed.codePoints().toArray();
        if (codePoints.length > maxRunes) {
            return "";
        }
        return trimmed;
    }

    private static String stripFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
