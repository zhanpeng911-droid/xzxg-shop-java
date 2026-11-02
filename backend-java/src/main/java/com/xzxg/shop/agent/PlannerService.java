package com.xzxg.shop.agent;

import com.xzxg.shop.config.ConfigService;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Hierarchical planner: route → guide/non-guide intent → answer model, using the
 * Go prompts and per-role model overrides. Falls back to keyword planning when the
 * model is unavailable or returns an invalid document.
 */
@Service
public class PlannerService {

    public record Plan(String route, String intent, String level, String secondaryLevel,
                       String reasoning, String answerModel) {
        public String referenceIntent() {
            return intent == null || intent.isBlank() ? route : intent;
        }
    }

    private final LlmClient llm;
    private final PromptService prompts;
    private final ConfigService configs;
    private final ObjectMapper objectMapper;

    public PlannerService(LlmClient llm, PromptService prompts, ConfigService configs,
                          ObjectMapper objectMapper) {
        this.llm = llm;
        this.prompts = prompts;
        this.configs = configs;
        this.objectMapper = objectMapper;
    }

    public Plan plan(String query) {
        Plan fallback = fallbackPlan(query);
        if (!llm.enabled()) {
            return fallback;
        }
        String route = classifyRoute(query, fallback.route());
        if ("guide".equals(route)) {
            Plan guide = classifyGuide(query, fallback);
            return new Plan("guide", guide.intent(), guide.level(), guide.secondaryLevel(),
                    guide.reasoning(), answerModel("guide", guide.intent()));
        }
        Plan nonGuide = classifyNonGuide(query, fallback);
        return new Plan("non_guide", nonGuide.intent(), nonGuide.level(),
                nonGuide.secondaryLevel(), nonGuide.reasoning(),
                answerModel("non_guide", nonGuide.intent()));
    }

    private String classifyRoute(String query, String fallback) {
        try {
            String content = llm.complete(modelForRole("planner_route", llm.smallModel()),
                    List.of(new LlmClient.Message("system", prompts.content(PromptSeeder.ROUTE)),
                            new LlmClient.Message("user", "用户问题：" + query
                                    + "\n只输出字段：reasoning、route。")),
                    0.1);
            JsonNode node = json(content);
            String route = node == null ? "" : node.path("route").asString("");
            return switch (route.trim()) {
                case "guide", "non_guide" -> route.trim();
                default -> fallback;
            };
        } catch (Exception ex) {
            return fallback;
        }
    }

    private Plan classifyGuide(String query, Plan fallback) {
        try {
            String content = llm.complete(modelForRole("planner_guide_intent", llm.smallModel()),
                    List.of(new LlmClient.Message("system", prompts.content(PromptSeeder.GUIDE_INTENT)),
                            new LlmClient.Message("user", "用户问题：" + query
                                    + "\n只输出字段：reasoning、is_guide、intent、level、secondary_level。")),
                    0.1);
            JsonNode node = json(content);
            if (node == null) {
                return fallback;
            }
            String intent = normalizeGuideIntent(node.path("intent").asString(""),
                    node.path("level").asString(""), node.path("secondary_level").asString(""));
            if (intent.isEmpty()) {
                return fallback;
            }
            return new Plan("guide", intent, node.path("level").asString(""),
                    node.path("secondary_level").asString(""),
                    node.path("reasoning").asString(""), "");
        } catch (Exception ex) {
            return fallback;
        }
    }

    private Plan classifyNonGuide(String query, Plan fallback) {
        try {
            String content = llm.complete(modelForRole("planner_non_guide_intent", llm.smallModel()),
                    List.of(new LlmClient.Message("system",
                                    prompts.content(PromptSeeder.NON_GUIDE_INTENT)),
                            new LlmClient.Message("user", "用户问题：" + query
                                    + "\n只输出字段：reasoning、intent。")),
                    0.1);
            JsonNode node = json(content);
            if (node == null) {
                return fallback;
            }
            String intent = normalizeNonGuideIntent(node.path("intent").asString(""),
                    fallback.intent());
            return new Plan("non_guide", intent, "", "", node.path("reasoning").asString(""), "");
        } catch (Exception ex) {
            return fallback;
        }
    }

    private Plan fallbackPlan(String query) {
        PlannerService.Plan heuristic = AgentRuntime.plan(query);
        return new Plan(heuristic.route(), heuristic.intent(), "", "", "keyword fallback", "");
    }

    private String answerModel(String route, String intent) {
        if ("non_guide".equals(route)) {
            return modelForRole("react_non_guide", llm.smallModel());
        }
        return modelForRole("react_guide", llm.largeModel());
    }

    private String modelForRole(String role, String fallback) {
        for (String key : List.of("ai.model." + role, "agent.model." + role)) {
            String value = configs.stringValue(key, "");
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fallback;
    }

    static String normalizeGuideIntent(String intent, String level, String secondary) {
        String normalizedIntent = intent == null ? "" : intent.trim();
        String normalizedLevel = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        String normalizedSecondary = secondary == null ? ""
                : secondary.trim().toUpperCase(Locale.ROOT);
        switch (normalizedIntent) {
            case "product_deep", "compare_decide", "outfit_styling", "category_shop_brand",
                    "category_shop_no_brand", "category_shop_complex", "scene_solution",
                    "open_explore" -> {
                return normalizedIntent;
            }
            case "category_shop" -> {
                return categoryIntent(normalizedSecondary);
            }
            default -> {
            }
        }
        return switch (normalizedLevel) {
            case "P1" -> "product_deep";
            case "P2" -> "compare_decide";
            case "P3" -> "outfit_styling";
            case "P4" -> categoryIntent(normalizedSecondary);
            case "P5" -> "scene_solution";
            case "P6" -> "open_explore";
            default -> "";
        };
    }

    private static String categoryIntent(String secondary) {
        return switch (secondary) {
            case "P4A" -> "category_shop_brand";
            case "P4C" -> "category_shop_complex";
            default -> "category_shop_no_brand";
        };
    }

    static String normalizeNonGuideIntent(String intent, String fallback) {
        String value = intent == null ? "" : intent.trim();
        return switch (value) {
            case "cart_add", "cart_remove", "cart_update_quantity" -> "cart_service";
            case "checkout_confirm" -> "order_service";
            case "cart_service", "order_service", "coupon_service", "review_service",
                    "after_sales_service", "account_service", "navigation_service",
                    "chitchat", "unsupported" -> value;
            case "non_guide", "" -> "non_guide";
            default -> fallback == null || fallback.isBlank() ? "unsupported" : fallback;
        };
    }

    private JsonNode json(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(content.substring(start, end + 1));
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
