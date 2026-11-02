package com.xzxg.shop.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolPolicyTest {

    @Test
    void guideIntentsCannotAddToCartOrCheckout() {
        for (String intent : new String[]{"product_deep", "compare_decide", "outfit_styling",
                "category_shop_brand", "category_shop_no_brand", "category_shop_complex",
                "scene_solution", "open_explore"}) {
            ToolPolicy.Policy policy = ToolPolicy.forPlan("guide", intent);
            assertThat(policy.allowsTool("search_products")).as(intent).isTrue();
            assertThat(policy.allowsTool("add_cart_item")).as(intent).isFalse();
            assertThat(policy.allowsTool("checkout")).as(intent).isFalse();
        }
    }

    @Test
    void cartServiceAllowsCartToolsButNotProductSearch() {
        ToolPolicy.Policy policy = ToolPolicy.forPlan("non_guide", "cart_service");
        assertThat(policy.allowsTool("get_cart")).isTrue();
        assertThat(policy.allowsTool("add_cart_item")).isTrue();
        assertThat(policy.allowsTool("checkout")).isTrue();
        assertThat(policy.allowsTool("search_products")).isFalse();
    }

    @Test
    void orderServiceAllowsPaymentButNotCartMutation() {
        ToolPolicy.Policy policy = ToolPolicy.forPlan("non_guide", "order_service");
        assertThat(policy.allowsTool("pay_order")).isTrue();
        assertThat(policy.allowsTool("checkout")).isTrue();
        assertThat(policy.allowsTool("add_cart_item")).isFalse();
    }

    @Test
    void navigationOnlyAllowsSkills() {
        ToolPolicy.Policy policy = ToolPolicy.forPlan("non_guide", "navigation_service");
        assertThat(policy.tools()).isEmpty();
        assertThat(policy.allowsSkill("navigate_orders")).isTrue();
        assertThat(policy.allowsTool("search_products")).isFalse();
    }

    @Test
    void unknownIntentFallsBackToRoutePolicy() {
        ToolPolicy.Policy policy = ToolPolicy.forPlan("guide", "made_up_intent");
        assertThat(policy.allowsTool("search_products")).isTrue();
        assertThat(policy.allowsTool("add_cart_item")).isFalse();
    }

    @Test
    void plannerClassifiesCommonQueries() {
        assertThat(AgentRuntime.plan("推荐拍照手机").intent()).isEqualTo("category_shop_no_brand");
        assertThat(AgentRuntime.plan("帮我对比这两款").intent()).isEqualTo("compare_decide");
        assertThat(AgentRuntime.plan("看看购物车").intent()).isEqualTo("cart_service");
        assertThat(AgentRuntime.plan("我的订单到哪了").intent()).isEqualTo("order_service");
        assertThat(AgentRuntime.plan("有什么优惠券").intent()).isEqualTo("coupon_service");
        assertThat(AgentRuntime.plan("我要退款").intent()).isEqualTo("after_sales_service");
        assertThat(AgentRuntime.plan("打开订单页").intent()).isEqualTo("navigation_service");
    }
}
