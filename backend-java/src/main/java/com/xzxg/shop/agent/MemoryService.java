package com.xzxg.shop.agent;

import com.xzxg.shop.config.ConfigService;
import com.xzxg.shop.domain.AgentRun;
import com.xzxg.shop.domain.ChatSession;
import com.xzxg.shop.domain.ProductCard;
import com.xzxg.shop.domain.UserMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Short-term memory retrieval and rolling session summary, ported from
 * {@code agent/memory.go}. Uses {@code memory_retrieval}/{@code memory_summary}
 * model roles and never lets history trigger cart/order actions by itself.
 */
@Service
public class MemoryService {

    public record Result(boolean hasRelevantMemory, String summary,
                         List<String> referencedProductIds, List<String> usedRecordIds,
                         List<AgentRepository.MemoryRecord> records) {
        public static Result empty(List<AgentRepository.MemoryRecord> records) {
            return new Result(false, "", List.of(), List.of(), records);
        }
    }

    private final AgentRepository repository;
    private final LlmClient llm;
    private final PromptService prompts;
    private final ConfigService configs;
    private final ObjectMapper objectMapper;

    public MemoryService(AgentRepository repository, LlmClient llm, PromptService prompts,
                         ConfigService configs, ObjectMapper objectMapper) {
        this.repository = repository;
        this.llm = llm;
        this.prompts = prompts;
        this.configs = configs;
        this.objectMapper = objectMapper;
    }

    public Result retrieve(AgentRun run, String query) {
        List<AgentRepository.MemoryRecord> records = repository.recentConversations(
                run.accountId(), run.sessionId(), intConfig("memory.window_turns", 5));
        if (records.isEmpty()) {
            return Result.empty(records);
        }
        if (!llm.enabled()) {
            return heuristic(query, records);
        }
        try {
            String content = llm.complete(modelForRole("memory_retrieval", llm.smallModel()),
                    List.of(new LlmClient.Message("system",
                                    prompts.content(PromptSeeder.MEMORY_RETRIEVAL)),
                            new LlmClient.Message("user",
                                    memoryUserPrompt(query, records))), 0.1);
            JsonNode node = json(content);
            if (node == null) {
                return heuristic(query, records);
            }
            boolean relevant = node.path("has_relevant_memory").asBoolean(false);
            String summary = node.path("memory_summary").asString("").trim();
            List<String> referenced = filterProductIds(node.path("referenced_product_ids"),
                    records);
            List<String> used = filterRecordIds(node.path("used_record_ids"), records);
            if (relevant && summary.isEmpty() && referenced.isEmpty()) {
                relevant = false;
            }
            return new Result(relevant, summary, referenced, used, records);
        } catch (Exception ex) {
            return heuristic(query, records);
        }
    }

    public String formatQuery(String query, Result memory) {
        if (!memory.hasRelevantMemory()) {
            return query;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("当前用户问题：\n").append(query.trim()).append("\n\n");
        builder.append("相关会话记忆：\n");
        if (!memory.summary().isBlank()) {
            builder.append("- ").append(memory.summary().trim()).append('\n');
        }
        if (!memory.referencedProductIds().isEmpty()) {
            builder.append("\n相关历史商品（后续如需加购或引用商品，优先使用这里的 item_id/product_id）：\n");
            Map<String, ProductRef> refs = productRefs(memory.records());
            for (String id : memory.referencedProductIds()) {
                ProductRef ref = refs.get(id);
                if (ref == null) {
                    continue;
                }
                builder.append("- 来自第").append(ref.turn()).append("轮的第")
                        .append(ref.index()).append("个商品 item_id=").append(id);
                if (ref.card() != null) {
                    builder.append(" sku_id=").append(ref.card().skuId())
                            .append(" name=").append(ref.card().name())
                            .append(" price=").append(ref.card().price());
                }
                builder.append('\n');
            }
        }
        builder.append("\n记忆使用规则：只把相关会话记忆作为指代消解和上下文补充；"
                + "最终回答仍直接回应当前用户问题，不要复述这些规则。");
        return builder.toString();
    }

    public String needSummary(AgentRun run, String query, PlannerService.Plan plan) {
        if (!llm.enabled()) {
            return "";
        }
        String prompt = """
                你是电商导购的需求理解摘要器。根据用户问题和已识别意图，输出给用户看的需求理解摘要。
                要求：
                - 只输出一段中文，50-90字。
                - 概括用户想买什么、核心偏好、预算/场景/功能/外观/风险等约束。
                - 不输出内部意图名、模型思考、工具名、JSON 或列表。
                - 不编造用户没有表达或上下文没有提供的信息。
                """;
        try {
            String content = llm.complete(modelForRole("need_summary", llm.smallModel()),
                    List.of(new LlmClient.Message("system", prompt),
                            new LlmClient.Message("user", "用户问题：" + query
                                    + "\n路由：" + plan.route()
                                    + "\n意图：" + plan.referenceIntent()
                                    + "\n层级：" + plan.level()
                                    + "\n二级层级：" + plan.secondaryLevel()
                                    + "\n模型分类理由：" + plan.reasoning())),
                    0.2);
            return cleanSummary(content);
        } catch (Exception ex) {
            return "";
        }
    }

    public void updateSessionSummary(AgentRun run, UserMessage message, String finalAnswer) {
        if (!llm.enabled() || finalAnswer == null || finalAnswer.isBlank()) {
            return;
        }
        ChatSession session = repository.getSession(run.accountId(), run.sessionId())
                .orElse(null);
        if (session == null) {
            return;
        }
        try {
            String content = llm.complete(modelForRole("memory_summary", llm.smallModel()),
                    List.of(new LlmClient.Message("system",
                                    prompts.content(PromptSeeder.SESSION_SUMMARY)),
                            new LlmClient.Message("user", "旧摘要：" + session.summary()
                                    + "\n\n最新用户问题：" + message.content()
                                    + "\n\n最新助手回答：" + truncate(finalAnswer, 1800))),
                    0.1);
            JsonNode node = json(content);
            String summary = node == null ? "" : node.path("summary").asString("").trim();
            if (summary.isEmpty()) {
                return;
            }
            repository.updateSessionSummary(run.accountId(), run.sessionId(), session.title(),
                    truncate(summary, 260));
        } catch (Exception ignored) {
            // summary is best-effort; the next run will retry
        }
    }

    private Result heuristic(String query, List<AgentRepository.MemoryRecord> records) {
        if (!looksMemoryDependent(query)) {
            return Result.empty(records);
        }
        AgentRepository.MemoryRecord latest = records.get(records.size() - 1);
        return new Result(true, "当前问题承接上一轮对话：" + truncate(latest.userQuery(), 80),
                latest.productIds(), List.of(latest.runId()), records);
    }

    private String memoryUserPrompt(String query, List<AgentRepository.MemoryRecord> records) {
        StringBuilder builder = new StringBuilder();
        builder.append("当前用户问题：").append(query.trim())
                .append("\n\n最近历史候选（按会话轮次从早到晚排列；带【最新一轮】的是当前问题之前最近的一轮）：\n");
        int maxChars = intConfig("memory.max_turn_chars", 1200);
        for (int i = 0; i < records.size(); i++) {
            AgentRepository.MemoryRecord record = records.get(i);
            String label = "第" + (i + 1) + "轮" + (i == records.size() - 1 ? "【最新一轮】" : "");
            builder.append('\n').append(label).append("：record_id=")
                    .append(record.runId()).append(" created_at=").append(record.createdAt())
                    .append('\n');
            builder.append("用户：").append(truncate(record.userQuery(), maxChars / 3)).append('\n');
            builder.append("助手：").append(truncate(record.finalAnswer(), maxChars));
            if (!record.productIds().isEmpty() || !record.productRefs().isEmpty()) {
                builder.append("\n本轮商品顺序：");
                for (int index = 0; index < record.productIds().size(); index++) {
                    String id = record.productIds().get(index);
                    ProductCard card = findCard(record.productRefs(), id);
                    builder.append("\n").append(index + 1).append(". item_id=").append(id);
                    if (card != null) {
                        builder.append(" sku_id=").append(card.skuId())
                                .append(" name=").append(card.name())
                                .append(" price=").append(card.price());
                    }
                }
            }
            builder.append('\n');
        }
        builder.append("\n轮次使用提示：如果当前问题出现“第一个/第二个/第N个 + 品类词”，"
                + "优先选择最近一轮与该品类词或主题匹配的推荐轮次，并按该轮“本轮商品顺序”"
                + "解析商品 ID；不要被更近但不同品类或不同动作的轮次覆盖。");
        return builder.toString();
    }

    private List<String> filterProductIds(JsonNode node,
                                          List<AgentRepository.MemoryRecord> records) {
        List<String> allowed = new ArrayList<>();
        for (AgentRepository.MemoryRecord record : records) {
            allowed.addAll(record.productIds());
        }
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String id = item.asString("").trim();
                if (!id.isEmpty() && allowed.contains(id) && !result.contains(id)) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    private List<String> filterRecordIds(JsonNode node,
                                         List<AgentRepository.MemoryRecord> records) {
        List<String> allowed = records.stream().map(AgentRepository.MemoryRecord::runId).toList();
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String id = item.asString("").trim();
                if (!id.isEmpty() && allowed.contains(id) && !result.contains(id)) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    private static Map<String, ProductRef> productRefs(
            List<AgentRepository.MemoryRecord> records) {
        Map<String, ProductRef> refs = new LinkedHashMap<>();
        for (int turn = 0; turn < records.size(); turn++) {
            AgentRepository.MemoryRecord record = records.get(turn);
            for (int index = 0; index < record.productIds().size(); index++) {
                String id = record.productIds().get(index);
                ProductCard card = findCard(record.productRefs(), id);
                refs.putIfAbsent(id, new ProductRef(turn + 1, index + 1, card));
            }
        }
        return refs;
    }

    private static ProductCard findCard(List<ProductCard> cards, String productId) {
        for (ProductCard card : cards) {
            if (productId.equals(card.productId())) {
                return card;
            }
        }
        return null;
    }

    private static boolean looksMemoryDependent(String query) {
        String value = query == null ? "" : query;
        for (String keyword : List.of("刚才", "上面", "前面", "那个", "这个", "第二个",
                "第一个", "第三个", "加购物车", "加入购物车", "继续", "换一个", "再来")) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
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

    private int intConfig(String key, int fallback) {
        try {
            return Integer.parseInt(configs.stringValue(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String cleanSummary(String content) {
        if (content == null) {
            return "";
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = new ObjectMapperShim().parse(content.substring(start, end + 1));
                String summary = node.path("summary").asString("");
                if (!summary.isBlank()) {
                    return summary.trim();
                }
            } catch (RuntimeException ignored) {
                // fall through to plain text
            }
        }
        return content.trim();
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

    private record ProductRef(int turn, int index, ProductCard card) {
    }

    private static String truncate(String value, int maxRunes) {
        String text = value == null ? "" : value.trim();
        if (text.codePointCount(0, text.length()) <= maxRunes) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxRunes));
    }

    private static final class ObjectMapperShim {
        private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();

        JsonNode parse(String value) {
            return mapper.readTree(value);
        }
    }
}
