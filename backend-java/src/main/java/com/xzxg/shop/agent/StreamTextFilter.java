package com.xzxg.shop.agent;

import com.xzxg.shop.domain.AgentBlock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Port of {@code streamTextFilter}: strips protocol tags across arbitrary delta
 * boundaries, emits whitelisted item IDs, parses structured blocks, and removes
 * markdown fences / sentinel words.
 */
public final class StreamTextFilter {

    private final Set<String> allowedItemIds = new LinkedHashSet<>();
    private final Set<String> emittedItemIds = new LinkedHashSet<>();
    private final Consumer<String> onItem;
    private final Consumer<AgentBlock> onBlock;
    private final ObjectMapper objectMapper;

    private boolean inTag;
    private boolean inItem;
    private boolean inBuyer;
    private final StringBuilder tagBuffer = new StringBuilder();
    private final StringBuilder itemBuffer = new StringBuilder();
    private String structuredTag = "";
    private final StringBuilder structuredBuffer = new StringBuilder();
    private String fenceCarry = "";

    public StreamTextFilter(List<String> allowedProductIds, Consumer<String> onItem,
                            Consumer<AgentBlock> onBlock, ObjectMapper objectMapper) {
        if (allowedProductIds != null) {
            for (String id : allowedProductIds) {
                if (id != null && !id.isBlank()) {
                    allowedItemIds.add(id.trim());
                }
            }
        }
        this.onItem = onItem;
        this.onBlock = onBlock;
        this.objectMapper = objectMapper;
    }

    public String clean(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < delta.length(); ) {
            int codePoint = delta.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == '<') {
                inTag = true;
                tagBuffer.setLength(0);
                continue;
            }
            if (inTag && codePoint == '>') {
                inTag = false;
                String tag = tagBuffer.toString().trim().toLowerCase(Locale.ROOT);
                handleTag(tag);
                continue;
            }
            if (inTag) {
                tagBuffer.appendCodePoint(codePoint);
                continue;
            }
            if (!structuredTag.isEmpty()) {
                structuredBuffer.appendCodePoint(codePoint);
                continue;
            }
            if (inItem) {
                itemBuffer.appendCodePoint(codePoint);
                continue;
            }
            if (inBuyer) {
                continue;
            }
            out.appendCodePoint(codePoint);
        }
        String clean = out.toString()
                .replace("special_word", "")
                .replace("special word", "")
                .replace("special", "")
                .replace("_word", "")
                .replace("（）", "")
                .replace("()", "");
        return stripMarkdownFenceLines(clean);
    }

    private void handleTag(String tag) {
        switch (tag) {
            case "item" -> {
                inItem = true;
                itemBuffer.setLength(0);
            }
            case "/item" -> {
                String id = itemBuffer.toString().trim();
                if (allowedItemIds.contains(id) && !emittedItemIds.contains(id)) {
                    emittedItemIds.add(id);
                    if (onItem != null) {
                        onItem.accept(id);
                    }
                }
                inItem = false;
                itemBuffer.setLength(0);
            }
            case "buyer" -> inBuyer = true;
            case "/buyer" -> inBuyer = false;
            default -> {
                if (isStructuredFinalTag(tag)) {
                    structuredTag = tag;
                    structuredBuffer.setLength(0);
                    return;
                }
                if (!structuredTag.isEmpty() && tag.equals("/" + structuredTag)) {
                    AgentBlock block = blockFromStructuredFinalTag(structuredTag,
                            structuredBuffer.toString());
                    if (block != null && onBlock != null) {
                        onBlock.accept(block);
                    }
                    structuredTag = "";
                    structuredBuffer.setLength(0);
                }
            }
        }
    }

    private AgentBlock blockFromStructuredFinalTag(String tag, String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            String title = node.path("title").asString("").trim();
            String message = node.path("message").asString("").trim();
            Map<String, Object> summary = node.has("summary")
                    ? objectMapper.convertValue(node.get("summary"),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    }) : null;
            Map<String, Object> action = node.has("action")
                    ? objectMapper.convertValue(node.get("action"),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    }) : null;
            List<Map<String, Object>> items = node.has("items")
                    ? objectMapper.convertValue(node.get("items"),
                    new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    }) : null;
            return new AgentBlock(tag, title.isEmpty() ? defaultTitle(tag) : title,
                    null, null, null, null, null, null, null, items, summary, null, null,
                    null, message, action);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isStructuredFinalTag(String tag) {
        return switch (tag) {
            case "coupon_list", "discount_preview", "review_summary", "after_sales_policy",
                    "navigation_action" -> true;
            default -> false;
        };
    }

    private static String defaultTitle(String tag) {
        return switch (tag) {
            case "coupon_list" -> "优惠券";
            case "discount_preview" -> "优惠试算";
            case "review_summary" -> "评价摘要";
            case "after_sales_policy" -> "售后规则";
            case "navigation_action" -> "页面入口";
            default -> "服务信息";
        };
    }

    private String stripMarkdownFenceLines(String text) {
        if (text.isEmpty() && fenceCarry.isEmpty()) {
            return "";
        }
        String combined = fenceCarry + text;
        fenceCarry = "";
        String[] lines = combined.split("(?<=\n)", -1);
        int count = lines.length;
        if (count > 0 && !combined.endsWith("\n")) {
            String last = lines[count - 1];
            if (isMarkdownFencePrefix(last.trim())) {
                fenceCarry = last;
                count--;
            }
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (!isMarkdownFenceLine(lines[i].trim())) {
                builder.append(lines[i]);
            }
        }
        return builder.toString();
    }

    private static boolean isMarkdownFenceLine(String line) {
        if (line.isEmpty()) {
            return false;
        }
        if ("```".equals(line)) {
            return true;
        }
        if (line.startsWith("```")) {
            String lang = line.substring(3).trim();
            return "markdown".equals(lang) || "md".equals(lang);
        }
        return false;
    }

    private static boolean isMarkdownFencePrefix(String line) {
        if (line.isEmpty()) {
            return false;
        }
        for (String fence : List.of("```", "```markdown", "```md")) {
            if (fence.startsWith(line)) {
                return true;
            }
        }
        return isMarkdownFenceLine(line);
    }
}
