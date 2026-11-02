package com.xzxg.shop.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Full Go prompt defaults extracted from {@code configcenter/center.go} into
 * {@code prompts/go-defaults.json}. DB-published prompts take priority over these.
 */
@Component
public class PromptCatalog {

    private static final Map<String, String> CONSTANT_KEYS = constantKeys();

    private final Map<String, String> defaultsByPromptKey = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;

    public PromptCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        load();
    }

    public String defaultFor(String promptKey) {
        return defaultsByPromptKey.get(promptKey);
    }

    public String intentPrompt(String intent) {
        return defaultsByPromptKey.get("agent.prompt.intent." + intent);
    }

    public Map<String, PromptRepository.PromptSeed> allSeeds() {
        Map<String, PromptRepository.PromptSeed> seeds = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : defaultsByPromptKey.entrySet()) {
            String key = entry.getKey();
            String title = key.startsWith("agent.prompt.intent.")
                    ? "意图 Prompt：" + key.substring("agent.prompt.intent.".length())
                    : key;
            seeds.put(key, new PromptRepository.PromptSeed(key, title, entry.getValue(),
                    "Go 默认 Prompt：" + key));
        }
        return seeds;
    }

    public List<String> legacyDefaults(String key) {
        return switch (key) {
            case "agent.prompt.route" -> List.of(
                    "判断用户请求属于 guide（商品导购）还是 non_guide（购物车、订单、优惠、售后等确定性操作）。");
            case "agent.prompt.guide_intent" -> List.of(
                    "在 guide 路由内识别商品推荐、对比、知识问答等细分意图。");
            case "agent.prompt.non_guide_intent" -> List.of(
                    "识别购物车、订单、支付、取消、收货、优惠券、评价等确定性意图。");
            case "agent.prompt.main_template" -> List.of(
                    "你是 xzxg-shop 的导购 Agent，回答要简洁、可执行，不编造商品或订单信息。");
            case "agent.prompt.tool_call_protocol" -> List.of(
                    "只输出 JSON。工具调用：{\"type\":\"tool_call\",\"tool\":\"工具名\",\"arguments\":{}}；"
                            + "技能调用：{\"type\":\"skill_call\",\"skill\":\"技能名\"}；"
                            + "最终回答：{\"type\":\"final\",\"text\":\"回答\"}。",
                    "工具调用只输出 JSON：{\"type\":\"tool_call\",\"tool\":\"工具名\",\"arguments\":{}}；"
                            + "技能调用只输出 JSON：{\"type\":\"skill_call\",\"skill\":\"技能名\"}；"
                            + "最终回答只输出 <final>回答</final>，标签外不要输出正文。");
            case "agent.prompt.final_output_rules" -> List.of(
                    "用中文回答，先给结论再给理由；涉及价格、库存、订单状态时必须来自工具结果。");
            case "agent.prompt.followups" -> List.of(
                    "生成三个简短、与当前问题相关的追问。");
            default -> List.of();
        };
    }

    private void load() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/go-defaults.json");
            JsonNode root = objectMapper.readTree(resource.getInputStream());
            for (Map.Entry<String, String> entry : CONSTANT_KEYS.entrySet()) {
                JsonNode node = root.get(entry.getKey());
                if (node != null && node.isString() && !node.asString().isBlank()) {
                    defaultsByPromptKey.put(entry.getValue(), node.asString());
                }
            }
            JsonNode intents = root.get("__intents__");
            if (intents != null && intents.isObject()) {
                intents.forEachEntry((intent, content) ->
                        defaultsByPromptKey.put("agent.prompt.intent." + intent,
                                content.asString()));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("cannot load prompts/go-defaults.json", ex);
        }
    }

    private static Map<String, String> constantKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("DefaultRoutePrompt", "agent.prompt.route");
        keys.put("DefaultGuideIntentPrompt", "agent.prompt.guide_intent");
        keys.put("DefaultNonGuideIntentPrompt", "agent.prompt.non_guide_intent");
        keys.put("DefaultMainAgentTemplatePrompt", "agent.prompt.main_template");
        keys.put("DefaultToolCallProtocolPrompt", "agent.prompt.tool_call_protocol");
        keys.put("DefaultFinalOutputRulesPrompt", "agent.prompt.final_output_rules");
        keys.put("DefaultNonGuideFinalOutputRulesPrompt",
                "agent.prompt.non_guide_final_output_rules");
        keys.put("DefaultIntentToolPolicyPrompt", "agent.prompt.intent_tool_policy");
        keys.put("DefaultFollowupsPrompt", "agent.prompt.followups");
        keys.put("DefaultGuideSuggestionsPrompt", "agent.prompt.guide_suggestions");
        keys.put("DefaultMemoryRetrievalPrompt", "agent.prompt.memory_retrieval");
        keys.put("DefaultSessionSummaryPrompt", "agent.prompt.session_summary");
        keys.put("DefaultAnswerBasePrompt", "agent.prompt.answer_base");
        keys.put("DefaultToolProtocolPrompt", "agent.prompt.tool_protocol");
        return Map.copyOf(keys);
    }

    public List<String> keys() {
        return new ArrayList<>(defaultsByPromptKey.keySet());
    }
}
