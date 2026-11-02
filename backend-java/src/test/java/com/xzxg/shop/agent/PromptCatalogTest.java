package com.xzxg.shop.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PromptCatalogTest {

    private final PromptCatalog catalog = new PromptCatalog(JsonMapper.builder().build());

    @Test
    void loadsAllGoPromptConstantsAndIntentPrompts() {
        assertThat(catalog.keys()).hasSize(32);
        assertThat(catalog.defaultFor("agent.prompt.route")).contains("non_guide");
        assertThat(catalog.defaultFor("agent.prompt.guide_intent")).contains("P1");
        assertThat(catalog.defaultFor("agent.prompt.non_guide_intent")).contains("cart_service");
        assertThat(catalog.defaultFor("agent.prompt.main_template")).contains("ReAct");
        assertThat(catalog.defaultFor("agent.prompt.intent_tool_policy")).contains("category_shop_no_brand");
        assertThat(catalog.intentPrompt("cart_service")).contains("购物车");
        assertThat(catalog.intentPrompt("order_service")).contains("订单");
        assertThat(catalog.intentPrompt("after_sales_service")).contains("售后");
        assertThat(catalog.allSeeds()).hasSize(32);
    }
}
