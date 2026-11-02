package com.xzxg.shop.agent;

import com.xzxg.shop.account.AccountRepository;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.AgentBlock;
import com.xzxg.shop.domain.AgentRun;
import com.xzxg.shop.domain.ProductCard;
import com.xzxg.shop.domain.SSEEvent;
import com.xzxg.shop.domain.ThoughtStep;
import com.xzxg.shop.domain.UserMessage;
import com.xzxg.shop.knowledge.KnowledgeService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ReAct runtime. When an API key is configured it uses the LLM protocol
 * ({@code tool_call} / {@code final}); otherwise a deterministic local fallback
 * keeps the guide usable.
 */
@Service
public class AgentRuntime {

    private static final Pattern PRODUCT_ID = Pattern.compile("(p_[A-Za-z0-9_]+)");
    private static final Pattern ORDER_ID = Pattern.compile("(ord_[0-9]+)");
    private static final int MAX_STEPS = 6;

    public interface Emitter {
        void emit(SSEEvent event) throws IOException;
    }

    private final AgentRepository repository;
    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final LlmClient llm;
    private final RiskService risk;
    private final AccountRepository accounts;
    private final KnowledgeService knowledge;
    private final PromptRepository prompts;
    private final ObjectMapper objectMapper;

    public AgentRuntime(AgentRepository repository, ToolRegistry tools, SkillRegistry skills,
                        LlmClient llm, RiskService risk, AccountRepository accounts,
                        KnowledgeService knowledge, PromptRepository prompts,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.tools = tools;
        this.skills = skills;
        this.llm = llm;
        this.risk = risk;
        this.accounts = accounts;
        this.knowledge = knowledge;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    public void stream(AgentRun run, UserMessage message, Emitter emit) throws IOException {
        repository.recordTrace(run.runId(), run.traceId(), run.accountId(), "run", "start",
                "", "ok", 0, "", "{\"session_id\":\"" + run.sessionId() + "\"}");
        emit.emit(new SSEEvent("message_start", run.runId(), run.sessionId(), message.messageId(),
                run.traceId(), null, null, null, null, null, null, null, null, null));

        RiskService.Result textRisk = risk.checkText(message.content());
        if (textRisk.blocked()) {
            emitText(run, textRisk.message(), emit);
            emit.emit(SSEEvent.block(run.runId(), Blocks.warning(textRisk.code(), textRisk.message())));
            emit.emit(SSEEvent.of("message_end", run.runId()));
            return;
        }
        Account account = accounts.findById(run.accountId()).orElse(null);
        RiskService.Result accountRisk = risk.checkAccount(account);
        if (accountRisk.blocked()) {
            emitText(run, accountRisk.message(), emit);
            emit.emit(SSEEvent.block(run.runId(),
                    Blocks.warning(accountRisk.code(), accountRisk.message())));
            emit.emit(SSEEvent.of("message_end", run.runId()));
            return;
        }
        if (account == null) {
            emitText(run, "账号不存在，无法继续导购。", emit);
            emit.emit(SSEEvent.of("message_end", run.runId()));
            return;
        }

        emitStatus(run, "intent", "正在理解你的需求", emit);
        String query = message.content() == null ? "" : message.content().trim();
        boolean nonGuide = isNonGuide(query);
        if (nonGuide) {
            deterministic(run, account, query, emit);
        } else if (llm.enabled()) {
            react(run, account, query, emit);
        } else {
            localGuide(run, query, emit);
        }
        emit.emit(new SSEEvent("followups", run.runId(), null, null, null, null, null, null,
                null, null, null, followups(nonGuide), null, null));
        emit.emit(SSEEvent.of("message_end", run.runId()));
    }

    private void localGuide(AgentRun run, String query, Emitter emit) throws IOException {
        ToolRegistry.Observation observation = tools.execute("search_products",
                Map.of("query", query, "limit", 5), accountOf(run));
        emitObservation(run, observation, emit);
        if (observation.result().get("items") instanceof List<?> items && !items.isEmpty()) {
            StringBuilder text = new StringBuilder("根据当前商品库，为你找到这些选择：");
            for (Object item : items) {
                if (item instanceof ProductCard card) {
                    text.append("\n- ").append(card.name()).append("（¥").append(card.price())
                            .append("）：").append(card.recommendReason());
                }
            }
            text.append("\n可以告诉我想重点比较的机型或预算，我再帮你缩小范围。");
            emitText(run, text.toString(), emit);
        } else {
            String fallback = "暂时没有找到完全匹配的商品。可以换个关键词，或告诉我在意拍照、续航还是预算。";
            var citations = knowledge.search(query, 3);
            if (!citations.isEmpty()) {
                emit.emit(SSEEvent.block(run.runId(),
                        Blocks.citationRefs(citations.stream().map(c -> c.chunkId()).toList())));
                fallback = fallback + "\n相关资料：" + citations.get(0).snippet();
            }
            emitText(run, fallback, emit);
        }
    }

    private void deterministic(AgentRun run, Account account, String query, Emitter emit)
            throws IOException {
        String lower = query.toLowerCase(Locale.ROOT);
        ToolRegistry.Observation observation;
        if (lower.contains("结算") || lower.contains("下单")) {
            observation = tools.execute("checkout", Map.of(), account);
        } else if (lower.contains("加购") || lower.contains("加入购物车")) {
            Matcher matcher = PRODUCT_ID.matcher(query);
            if (matcher.find()) {
                observation = tools.execute("add_cart_item",
                        Map.of("product_id", matcher.group(1), "quantity", 1), account);
            } else {
                observation = tools.execute("search_products",
                        Map.of("query", query, "limit", 5), account);
                emitObservation(run, observation, emit);
                emitText(run, "请先确认要加购的商品，我可以根据上面的结果继续。", emit);
                return;
            }
        } else if (lower.contains("支付") || lower.contains("付款")) {
            Matcher matcher = ORDER_ID.matcher(query);
            observation = matcher.find()
                    ? tools.execute("pay_order", Map.of("order_id", matcher.group(1), "method", "mock_balance"), account)
                    : tools.execute("list_orders", Map.of(), account);
        } else if (lower.contains("取消订单") || lower.contains("取消")) {
            Matcher matcher = ORDER_ID.matcher(query);
            observation = matcher.find()
                    ? tools.execute("cancel_order", Map.of("order_id", matcher.group(1), "reason", "用户取消"), account)
                    : tools.execute("list_orders", Map.of(), account);
        } else if (lower.contains("收货")) {
            Matcher matcher = ORDER_ID.matcher(query);
            observation = matcher.find()
                    ? tools.execute("confirm_receipt", Map.of("order_id", matcher.group(1)), account)
                    : tools.execute("list_orders", Map.of(), account);
        } else if (lower.contains("优惠券") || lower.contains("券")) {
            observation = tools.execute("list_user_coupons", Map.of(), account);
        } else if (lower.contains("促销") || lower.contains("活动")) {
            observation = tools.execute("list_promotions", Map.of(), account);
        } else if (lower.contains("购物车")) {
            observation = tools.execute("get_cart", Map.of(), account);
        } else {
            observation = tools.execute("list_orders", Map.of(), account);
        }
        emitObservation(run, observation, emit);
        emitText(run, observation.message(), emit);
    }

    private void react(AgentRun run, Account account, String query, Emitter emit)
            throws IOException {
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(new LlmClient.Message("system", systemPrompt()));
        var memory = repository.recentConversations(account.accountId(), run.sessionId(), 5);
        if (!memory.isEmpty()) {
            StringBuilder context = new StringBuilder("最近对话：");
            for (var record : memory) {
                context.append("\n用户：").append(record.userQuery());
                if (!record.finalAnswer().isBlank()) {
                    context.append("\n助手：").append(record.finalAnswer());
                }
            }
            messages.add(new LlmClient.Message("system", context.toString()));
        }
        messages.add(new LlmClient.Message("user", query));

        for (int step = 0; step < MAX_STEPS; step++) {
            String model = llm.smallModel();
            String raw;
            try {
                raw = llm.complete(model, messages, 1.0);
            } catch (Exception ex) {
                localGuide(run, query, emit);
                return;
            }
            JsonNode action = parseAction(raw);
            if (action != null && "skill_call".equals(action.path("type").asString())) {
                String skill = action.path("skill").asString("");
                ThoughtStep thought = new ThoughtStep("step_" + (step + 1), "执行技能 " + skill,
                        "running", null, null, null, step + 1);
                emit.emit(thinking(run.runId(), thought));
                ToolRegistry.Observation observation = skills.execute(skill);
                emit.emit(thinking(run.runId(), new ThoughtStep("step_" + (step + 1),
                        "执行技能 " + skill, "completed", observation.message(), null, null,
                        step + 1)));
                emitObservation(run, observation, emit);
                messages.add(new LlmClient.Message("assistant", raw));
                messages.add(new LlmClient.Message("user", "observation: "
                        + objectMapper.writeValueAsString(observation.result())));
                continue;
            }
            if (action != null && "tool_call".equals(action.path("type").asString())) {
                String tool = action.path("tool").asString("");
                Map<String, Object> args = action.has("arguments")
                        ? objectMapper.convertValue(action.get("arguments"),
                        new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                        })
                        : Map.of();
                ThoughtStep thought = new ThoughtStep("step_" + (step + 1), "调用工具 " + tool,
                        "running", null, null, null, step + 1);
                emit.emit(thinking(run.runId(), thought));
                ToolRegistry.Observation observation = tools.execute(tool, args, account);
                ThoughtStep done = new ThoughtStep("step_" + (step + 1), "调用工具 " + tool,
                        "completed", observation.message(), null, null, step + 1);
                emit.emit(thinking(run.runId(), done));
                emitObservation(run, observation, emit);
                messages.add(new LlmClient.Message("assistant", raw));
                messages.add(new LlmClient.Message("user", "observation: "
                        + objectMapper.writeValueAsString(observation.result())));
                continue;
            }
            String finalText = extractFinal(raw);
            if (finalText.isBlank()) {
                localGuide(run, query, emit);
                return;
            }
            emitText(run, finalText, emit);
            return;
        }
        emitText(run, "这个问题需要更多信息，可以再具体描述一下吗？", emit);
    }

    private void emitObservation(AgentRun run, ToolRegistry.Observation observation,
                                 Emitter emit) throws IOException {
        for (AgentBlock block : observation.blocks()) {
            emit.emit(SSEEvent.block(run.runId(), block));
        }
    }

    private void emitText(AgentRun run, String text, Emitter emit) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        int[] codePoints = text.codePoints().toArray();
        int chunkSize = 24;
        for (int start = 0; start < codePoints.length; start += chunkSize) {
            int end = Math.min(start + chunkSize, codePoints.length);
            String chunk = new String(codePoints, start, end - start);
            emit.emit(new SSEEvent("content_delta", run.runId(), null, null, null, null, null,
                    null, null, Blocks.text(chunk), null, null, null, null));
            emit.emit(SSEEvent.delta("text_delta", run.runId(), chunk));
        }
    }

    private void emitStatus(AgentRun run, String stage, String text, Emitter emit)
            throws IOException {
        emit.emit(new SSEEvent("status", run.runId(), null, null, null, stage, text, null,
                null, null, null, null, null, null));
    }

    private SSEEvent thinking(String runId, ThoughtStep step) {
        return new SSEEvent("thinking_delta", runId, null, null, null, null, null, null,
                null, null, step, null, null, null);
    }

    private JsonNode parseAction(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(raw.substring(start, end + 1));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String extractFinal(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?s)<final>(.*?)</final>").matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
                if (node.has("text")) {
                    return node.get("text").asString("");
                }
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        return raw.trim();
    }

    private String systemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompts.active(PromptSeeder.MAIN_TEMPLATE)
                .ifPresent(value -> prompt.append(value.content()).append('\n'));
        prompts.active(PromptSeeder.TOOL_PROTOCOL)
                .ifPresent(value -> prompt.append(value.content()).append('\n'));
        prompt.append("""
                可用工具：search_products、search_image_products、search_knowledge、get_cart、
                add_cart_item、update_cart_item、delete_cart_item、checkout、list_orders、get_order、
                pay_order、cancel_order、confirm_receipt、preview_discount、list_coupons、
                list_user_coupons、claim_coupon、list_promotions、list_product_reviews、
                create_product_review。
                工具调用格式：{"type":"tool_call","tool":"工具名","arguments":{...}}
                技能调用格式：{"type":"skill_call","skill":"navigate_cart|navigate_orders|navigate_products|coupon_help|order_help|after_sales_help"}
                最终回答格式：{"type":"final","text":"给用户的回答"}
                规则：没有商品依据时不要猜测商品 ID；不要重复执行历史对话里的加购或下单动作。
                """);
        return prompt.toString();
    }

    private static boolean isNonGuide(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.contains("购物车") || lower.contains("加购") || lower.contains("下单")
                || lower.contains("结算") || lower.contains("订单") || lower.contains("支付")
                || lower.contains("取消") || lower.contains("收货") || lower.contains("优惠券")
                || lower.contains("促销") || lower.contains("活动");
    }

    private static List<String> followups(boolean nonGuide) {
        return nonGuide
                ? List.of("查看我的订单", "看看购物车", "有什么优惠券")
                : List.of("帮我对比这几款", "有没有更便宜的", "先加入购物车");
    }

    private Account accountOf(AgentRun run) {
        return accounts.findById(run.accountId()).orElseThrow(ApiException::unauthorized);
    }
}
