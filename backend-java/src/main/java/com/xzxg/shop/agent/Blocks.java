package com.xzxg.shop.agent;

import com.xzxg.shop.domain.AgentBlock;
import com.xzxg.shop.domain.Cart;
import com.xzxg.shop.domain.Order;
import com.xzxg.shop.domain.ProductCard;
import java.util.List;
import java.util.Map;

/** Factory helpers for agent blocks (records have many optional fields). */
public final class Blocks {

    private Blocks() {
    }

    public static AgentBlock text(String content) {
        return block("text", null, null, null, null, null, content, null, null, null, null,
                null, null, null, null, null);
    }

    public static AgentBlock product(ProductCard product) {
        return block("product_card", null, product, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    public static AgentBlock productRefs(List<String> productIds) {
        return block("product_refs", null, null, productIds, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public static AgentBlock citationRefs(List<String> chunkIds) {
        return block("citation_refs", null, null, null, null, chunkIds, null, null, null,
                null, null, null, null, null, null, null);
    }

    public static AgentBlock action(String message, String name, String target, String label) {
        return block("action", null, null, null, null, null, null, null, null, null, null,
                null, null, null, message, Map.of("name", name, "target", target, "label", label));
    }

    public static AgentBlock cart(Cart cart) {
        return block("cart_state", null, null, null, null, null, null, null, null, null,
                null, cart, null, null, null, null);
    }

    public static AgentBlock orders(List<Order> orders) {
        return block("order_summary", null, null, null, null, null, null, null, null, null,
                null, null, orders, null, null, null);
    }

    public static AgentBlock warning(String code, String message) {
        return block("warning", null, null, null, null, null, null, null, null, null, null,
                null, null, code, message, null);
    }

    public static AgentBlock list(String type, List<Map<String, Object>> items,
                                  Map<String, Object> summary) {
        return block(type, null, null, null, null, null, null, null, null, items, summary,
                null, null, null, null, null);
    }

    public static AgentBlock comparison(List<String> columns, List<Map<String, Object>> rows) {
        return block("comparison_table", null, null, null, null, null, null, columns, rows,
                null, null, null, null, null, null, null);
    }

    private static AgentBlock block(String type, String title, ProductCard product,
                                    List<String> productIds, com.xzxg.shop.domain.Citation citation,
                                    List<String> chunkIds, String content, List<String> columns,
                                    List<Map<String, Object>> rows, List<Map<String, Object>> items,
                                    Map<String, Object> summary, Cart cart, List<Order> orders,
                                    String code, String message, Map<String, Object> action) {
        return new AgentBlock(type, title, product, productIds, citation, chunkIds, content,
                columns, rows, items, summary, cart, orders, code, message, action);
    }
}
