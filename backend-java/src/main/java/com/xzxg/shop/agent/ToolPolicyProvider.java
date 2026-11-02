package com.xzxg.shop.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Loads the Go intent tool policy JSON from the active prompt, with a safe fallback. */
@Service
public class ToolPolicyProvider {

    private final PromptService prompts;
    private final ObjectMapper objectMapper;

    public ToolPolicyProvider(PromptService prompts, ObjectMapper objectMapper) {
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    public ToolPolicy.Policy policy(String route, String intent) {
        Map<String, ToolPolicy.Policy> policies = parse(
                prompts.content(PromptSeeder.INTENT_TOOL_POLICY));
        List<String> keys = new ArrayList<>();
        keys.add(intent);
        keys.add(route);
        if ("guide".equals(route)) {
            keys.add("category_shop_no_brand");
        }
        keys.add("non_guide");
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            ToolPolicy.Policy policy = policies.get(key.trim());
            if (policy != null) {
                return policy;
            }
        }
        return ToolPolicy.forPlan(route, intent);
    }

    private Map<String, ToolPolicy.Policy> parse(String raw) {
        Map<String, ToolPolicy.Policy> policies = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return policies;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            root.forEachEntry((key, node) -> policies.put(key,
                    new ToolPolicy.Policy(sorted(node.path("tools")), sorted(node.path("skills")),
                            sorted(node.path("disabled")), sorted(node.path("focus")))));
        } catch (RuntimeException ex) {
            return Map.of();
        }
        return policies;
    }

    private static List<String> sorted(JsonNode node) {
        TreeSet<String> values = new TreeSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asString("").trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return List.copyOf(values);
    }
}
