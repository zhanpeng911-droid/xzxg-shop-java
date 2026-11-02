package com.xzxg.shop.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of {@code tool_policy.go} / {@code DefaultIntentToolPolicyPrompt}. The
 * policy is enforced before every tool/skill call, not only written into prompts.
 */
public final class ToolPolicy {

    public record Policy(List<String> tools, List<String> skills, List<String> disabled,
                         List<String> focus) {
        public boolean allowsTool(String tool) {
            return tools.contains(tool) && !disabled.contains(tool);
        }

        public boolean allowsSkill(String skill) {
            return skills.contains(skill) && !disabled.contains(skill);
        }
    }

    private static final String SP = "search_products";
    private static final String SI = "search_image_products";
    private static final String SK = "search_knowledge";
    private static final String GC = "get_cart";
    private static final String AC = "add_cart_item";
    private static final String UC = "update_cart_item";
    private static final String DC = "delete_cart_item";
    private static final String CO = "checkout";

    private static final Map<String, Policy> POLICIES = policies();

    private ToolPolicy() {
    }

    public static Policy forPlan(String route, String intent) {
        String effectiveIntent = intent == null ? "" : intent.trim();
        String effectiveRoute = route == null ? "" : route.trim();
        Policy policy = POLICIES.get(effectiveIntent);
        if (policy != null) {
            return policy;
        }
        policy = POLICIES.get(effectiveRoute);
        if (policy != null) {
            return policy;
        }
        if ("guide".equals(effectiveRoute)) {
            policy = POLICIES.get("category_shop_no_brand");
            if (policy != null) {
                return policy;
            }
        }
        policy = POLICIES.get("non_guide");
        return policy == null
                ? new Policy(List.of(SP, SK), List.of(), List.of(), List.of())
                : policy;
    }

    private static Map<String, Policy> policies() {
        Map<String, Policy> map = new LinkedHashMap<>();
        List<String> guideTools = List.of(SP, SI, SK);
        List<String> guideDisabled = List.of(GC, AC, UC, DC, CO);
        map.put("product_deep", new Policy(guideTools, List.of(), guideDisabled,
                List.of("围绕用户锚定的商品或型号调用 search_products；解释参数/材料/售后时调用 search_knowledge。")));
        map.put("compare_decide", new Policy(guideTools, List.of(), guideDisabled,
                List.of("分别检索候选商品，确保对比对象来自当前商品库；只围绕用户提到的维度比较。")));
        map.put("outfit_styling", new Policy(guideTools, List.of(), guideDisabled,
                List.of("把风格、场合、颜色、版型约束转成可检索品类；search_knowledge 补充搭配原则。")));
        map.put("category_shop_brand", new Policy(guideTools, List.of(), guideDisabled,
                List.of("检索时保留品牌/店铺和品类约束；需要品牌系列知识时调用 search_knowledge。")));
        map.put("category_shop_no_brand", new Policy(guideTools, List.of(), guideDisabled,
                List.of("按品类、预算、场景、人群和硬属性收敛 query；search_products 是主工具。")));
        map.put("category_shop_complex", new Policy(guideTools, List.of(), guideDisabled,
                List.of("先拆解预算、价格、属性、排除项等硬约束，再检索；不要推荐违反硬约束的商品。")));
        map.put("scene_solution", new Policy(guideTools, List.of(), guideDisabled,
                List.of("先建立场景清单框架，再对核心品类查资料和商品。")));
        map.put("open_explore", new Policy(guideTools, List.of(), guideDisabled,
                List.of("先把风格、IP、美学或模糊诉求收敛成可购买品类，再探索候选。")));

        map.put("non_guide", new Policy(
                List.of(GC, "list_orders", "list_user_coupons", "list_promotions", SK),
                List.of("navigate_cart", "navigate_orders", "navigate_products", "coupon_help",
                        "order_help", "after_sales_help"),
                List.of(SP, SI, AC, UC, DC, CO),
                List.of("非导购兜底：优先用只读工具获取真实状态，不要用固定模板回答订单/优惠/购物车问题。")));
        map.put("cart_service", new Policy(
                List.of(GC, AC, UC, DC, "preview_discount", CO), List.of("navigate_cart"),
                List.of(SP, SI, SK),
                List.of("加购必须有明确真实 product_id；改/删/局部结算前先 get_cart 定位 cart_item_id。",
                        "局部结算：get_cart → 目标项 selected=true → 非目标项 selected=false → checkout。")));
        map.put("order_service", new Policy(
                List.of(GC, "preview_discount", CO, "list_orders", "get_order", "pay_order",
                        "cancel_order", "confirm_receipt", SK),
                List.of("navigate_orders", "order_help"),
                List.of(SP, SI, AC, UC, DC),
                List.of("结算/提交订单必须调用 checkout；支付/取消/收货必须有明确 order_id，缺少时先 list_orders。")));
        map.put("coupon_service", new Policy(
                List.of("list_coupons", "list_user_coupons", "claim_coupon", "list_promotions",
                        "preview_discount"),
                List.of("coupon_help"),
                List.of(SP, SI, SK, AC, UC, DC, CO),
                List.of("查已领券用 list_user_coupons，可领券用 list_coupons，活动用 list_promotions；claim 必须有 coupon_id。")));
        map.put("review_service", new Policy(
                List.of("list_product_reviews", "create_product_review", "list_orders", "get_order"),
                List.of(),
                List.of(SP, SI, SK, GC, AC, UC, DC, CO),
                List.of("查评价必须有 product_id；发布评价必须定位已完成订单项。")));
        map.put("after_sales_service", new Policy(
                List.of("list_orders", "get_order", SK),
                List.of("after_sales_help", "order_help"),
                List.of(SP, SI, GC, AC, UC, DC, CO),
                List.of("售后/退款/保修先查规则，已购商品再查订单；不承诺未查到的结论。")));
        map.put("account_service", new Policy(List.of(SK), List.of(),
                List.of(SP, SI, GC, AC, UC, DC, CO),
                List.of("只回答规则和入口边界，不编造用户资料。")));
        map.put("navigation_service", new Policy(List.of(),
                List.of("navigate_cart", "navigate_orders", "navigate_products", "coupon_help",
                        "order_help", "after_sales_help"),
                List.of(SP, SI, SK, GC, AC, UC, DC, CO),
                List.of("只处理明确页面跳转/功能入口，不调用业务查询工具。")));
        map.put("chitchat", new Policy(List.of(), List.of(),
                List.of(SP, SI, SK, GC, AC, UC, DC, CO),
                List.of("简短自然回应，并引导用户说出商品、订单、优惠或售后诉求。")));
        map.put("unsupported", new Policy(List.of(), List.of(),
                List.of(SP, SI, SK, GC, AC, UC, DC, CO),
                List.of("说明暂不支持，并给出可继续使用的能力范围。")));
        return Map.copyOf(map);
    }

    public static Set<String> knownTools() {
        return Set.of(SP, SI, SK, GC, AC, UC, DC, CO, "list_orders", "get_order", "pay_order",
                "cancel_order", "confirm_receipt", "preview_discount", "list_coupons",
                "list_user_coupons", "claim_coupon", "list_promotions",
                "list_product_reviews", "create_product_review");
    }

    public static Set<String> knownSkills() {
        return Set.of("navigate_cart", "navigate_orders", "navigate_products", "coupon_help",
                "order_help", "after_sales_help");
    }
}
