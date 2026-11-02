package com.xzxg.shop.agent;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Follow-up question generation using the Go followups prompt. */
@Service
public class FollowupService {

    private final LlmClient llm;
    private final PromptService prompts;
    private final ObjectMapper objectMapper;

    public FollowupService(LlmClient llm, PromptService prompts, ObjectMapper objectMapper) {
        this.llm = llm;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    public List<String> generate(String query, String route, String intent,
                                 List<String> fallback) {
        if (!llm.enabled()) {
            return fallback;
        }
        try {
            String content = llm.complete(llm.smallModel(),
                    List.of(new LlmClient.Message("system",
                                    prompts.content(PromptSeeder.FOLLOWUPS)),
                            new LlmClient.Message("user", "用户问题：" + query
                                    + "\nroute=" + route + " intent=" + intent
                                    + "\n只输出 JSON 字符串数组。")),
                    0.3);
            String json = content == null ? "" : content.trim();
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return fallback;
            }
            JsonNode array = objectMapper.readTree(json.substring(start, end + 1));
            List<String> items = new ArrayList<>();
            for (JsonNode node : array) {
                String value = node.asString("").trim();
                if (!value.isEmpty() && !items.contains(value)) {
                    items.add(value);
                }
                if (items.size() >= 3) {
                    break;
                }
            }
            return items.isEmpty() ? fallback : items;
        } catch (Exception ex) {
            return fallback;
        }
    }
}
