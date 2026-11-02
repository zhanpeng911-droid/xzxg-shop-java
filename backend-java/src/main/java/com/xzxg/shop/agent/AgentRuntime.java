package com.xzxg.shop.agent;

import com.xzxg.shop.account.AccountRepository;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ReAct runtime with intent policy enforcement and cancellation checks between
 * every model call and tool execution.
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
    private final PromptService prompts;
    private final PlannerService planner;
    private final ToolPolicyProvider policyProvider;
    private final MemoryService memory;
    private final FollowupService followups;
    private final ObjectMapper objectMapper;

    public AgentRuntime(AgentRepository repository, ToolRegistry tools, SkillRegistry skills,
                        LlmClient llm, RiskService risk, AccountRepository accounts,
                        KnowledgeService knowledge, PromptService prompts,
                        PlannerService planner, ToolPolicyProvider policyProvider,
                        MemoryService memory, FollowupService followups,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.tools = tools;
        this.skills = skills;
        this.llm = llm;
        this.risk = risk;
        this.accounts = accounts;
        this.knowledge = knowledge;
        this.prompts = prompts;
        this.planner = planner;
        this.policyProvider = policyProvider;
        this.memory = memory;
        this.followups = followups;
        this.objectMapper = objectMapper;
    }

    public void stream(AgentRun run, UserMessage message, Emitter emit) throws IOException {
        stream(run, message, emit, () -> false);
    }

    public void stream(AgentRun run, UserMessage message, Emitter emit, BooleanSupplier canceled)
            throws IOException {
        repository.recordTrace(run.runId(), run.traceId(), run.accountId(), "run", "start",
                "", "ok", 0, "", "{\"session_id\":\"" + run.sessionId() + "\"}");
        emit.emit(new SSEEvent("message_start", run.runId(), run.sessionId(), message.messageId(),
                run.traceId(), null, null, null, null, null, null, null, null, null));

        if (canceled.getAsBoolean()) {
            emitCanceled(run, emit);
            return;
        }
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
        MemoryService.Result memoryResult = memory.retrieve(run, query);
        String effectiveQuery = memory.formatQuery(query, memoryResult);
        PlannerService.Plan plan = planner.plan(effectiveQuery);
        ToolPolicy.Policy policy = policyProvider.policy(plan.route(), plan.intent());
        repository.recordTrace(run.runId(), run.traceId(), run.accountId(), "planner", "selected",
                plan.answerModel(), "ok", 0, "",
                "{\"route\":\"" + plan.route() + "\",\"intent\":\"" + plan.intent()
                        + "\",\"level\":\"" + plan.level() + "\",\"secondary_level\":\""
                        + plan.secondaryLevel() + "\"}");
        if (memoryResult.hasRelevantMemory()) {
            repository.recordTrace(run.runId(), run.traceId(), run.accountId(), "memory",
                    "applied", "", "ok", 0, "",
                    "{\"referenced_product_ids\":" + objectMapper.writeValueAsString(
                            memoryResult.referencedProductIds()) + "}");
        }
        String needSummary = memory.needSummary(run, effectiveQuery, plan);
        if (!needSummary.isBlank()) {
            emit.emit(thinking(run.runId(), new ThoughtStep("intent", "分析用户需求", "done",
                    needSummary, null, null, 1)));
        }

        Set<String> allowedProductIds = new HashSet<>();
        Matcher explicitProducts = PRODUCT_ID.matcher(query);
        while (explicitProducts.find()) {
            allowedProductIds.add(explicitProducts.group(1));
        }
        allowedProductIds.addAll(memoryResult.referencedProductIds());
        try {
            if ("non_guide".equals(plan.route()) && !llm.enabled()) {
                deterministic(run, account, query, plan, policy, allowedProductIds, memoryResult,
                        canceled, emit);
            } else if (llm.enabled()) {
                react(run, account, effectiveQuery, plan, policy, allowedProductIds, canceled, emit);
            } else {
                localGuide(run, effectiveQuery, policy, allowedProductIds, emit);
            }
        } catch (CancellationException ex) {
            emitCanceled(run, emit);
            return;
        }
        if (canceled.getAsBoolean()) {
            emitCanceled(run, emit);
            return;
        }
        emit.emit(new SSEEvent("followups", run.runId(), null, null, null, null, null, null,
                null, null, null, followups.generate(query, plan.route(), plan.intent(),
                        followupFallback(plan)), null, null));
        emit.emit(SSEEvent.of("message_end", run.runId()));
    }

    private void localGuide(AgentRun run, String query, ToolPolicy.Policy policy,
                            Set<String> allowedProductIds, Emitter emit) throws IOException {
        if (!policy.allowsTool("search_products")) {
            emitText(run, "当前请求不在商品导购范围内，可以告诉我想买的商品或使用场景。", emit);
            return;
        }
        ToolRegistry.Observation observation = tools.execute("search_products",
                Map.of("query", query, "limit", 5), accountOf(run));
        rememberProducts(observation, allowedProductIds);
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

    private void deterministic(AgentRun run, Account account, String query,
                               PlannerService.Plan plan, ToolPolicy.Policy policy,
                               Set<String> allowedProductIds, MemoryService.Result memoryResult,
                               BooleanSupplier canceled, Emitter emit) throws IOException {
        String lower = query.toLowerCase(Locale.ROOT);
        if ("navigation_service".equals(plan.intent())) {
            String skill = navigationSkill(lower);
            executeSkillAndReply(run, skill, policy, emit);
            return;
        }
        String tool;
        Map<String, Object> args = Map.of();
        if (lower.contains("结算") || lower.contains("下单")) {
            tool = "checkout";
        } else if (lower.contains("加购") || lower.contains("加入购物车")) {
            Matcher matcher = PRODUCT_ID.matcher(query);
            if (matcher.find()) {
                tool = "add_cart_item";
                args = Map.of("product_id", matcher.group(1), "quantity", 1);
            } else if (memoryResult.hasRelevantMemory()
                    && !memoryResult.referencedProductIds().isEmpty()
                    && looksLikeMemoryReference(query)) {
                tool = "add_cart_item";
                args = Map.of("product_id", memoryResult.referencedProductIds().get(0),
                        "quantity", 1);
            } else {
                tool = "search_products";
                args = Map.of("query", query, "limit", 5);
            }
        } else if (lower.contains("支付") || lower.contains("付款")) {
            Matcher matcher = ORDER_ID.matcher(query);
            tool = matcher.find() ? "pay_order" : "list_orders";
            if (matcher.find(0)) {
                args = Map.of("order_id", matcher.group(1), "method", "mock_balance");
            }
        } else if (lower.contains("取消订单") || lower.contains("取消")) {
            Matcher matcher = ORDER_ID.matcher(query);
            tool = matcher.find() ? "cancel_order" : "list_orders";
            if (matcher.find(0)) {
                args = Map.of("order_id", matcher.group(1), "reason", "用户取消");
            }
        } else if (lower.contains("收货")) {
            Matcher matcher = ORDER_ID.matcher(query);
            tool = matcher.find() ? "confirm_receipt" : "list_orders";
            if (matcher.find(0)) {
                args = Map.of("order_id", matcher.group(1));
            }
        } else if (lower.contains("优惠券") || lower.contains("券")) {
            tool = "list_user_coupons";
        } else if (lower.contains("促销") || lower.contains("活动")) {
            tool = "list_promotions";
        } else if (lower.contains("评价") || lower.contains("评论")) {
            tool = "list_orders";
        } else if (lower.contains("购物车")) {
            tool = "get_cart";
        } else {
            tool = "list_orders";
        }

        if (!policy.allowsTool(tool)) {
            emitText(run, "当前请求需要的能力不在本轮可用范围内，可以说明具体要查看或操作的内容。", emit);
            return;
        }
        if ("add_cart_item".equals(tool)) {
            String productId = String.valueOf(args.getOrDefault("product_id", ""));
            if (productId.isBlank() || !allowedProductIds.contains(productId)) {
                emitText(run, "没有本轮检索确认过的商品 ID，不能直接加购；请先告诉我具体商品。", emit);
                return;
            }
        }
        if (canceled.getAsBoolean()) {
            throw new CancellationException("run canceled");
        }
        ToolRegistry.Observation observation = tools.execute(tool, args, account);
        rememberProducts(observation, allowedProductIds);
        emitObservation(run, observation, emit);
        emitText(run, observation.message(), emit);
    }

    private void executeSkillAndReply(AgentRun run, String skill, ToolPolicy.Policy policy,
                                      Emitter emit) throws IOException {
        if (!policy.allowsSkill(skill)) {
            emitText(run, "当前请求不在可用入口范围内。", emit);
            return;
        }
        ToolRegistry.Observation observation = skills.execute(skill);
        emitObservation(run, observation, emit);
        emitText(run, observation.message(), emit);
    }

    private void react(AgentRun run, Account account, String query,
                       PlannerService.Plan plan, ToolPolicy.Policy policy, Set<String> allowedProductIds,
                       BooleanSupplier canceled, Emitter emit) throws IOException {
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(new LlmClient.Message("system", systemPrompt(plan, policy)));
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
            if (canceled.getAsBoolean()) {
                throw new CancellationException("run canceled");
            }
            String raw;
            try {
                StreamAnswer answer = streamAnswer(run, messages, allowedProductIds, canceled, emit);
                raw = answer.raw();
                if (answer.streamed()) {
                    return;
                }
            } catch (CancellationException ex) {
                throw ex;
            } catch (Exception ex) {
                localGuide(run, query, policy, allowedProductIds, emit);
                return;
            }
            JsonNode action = parseAction(raw);
            if (action != null && "skill_call".equals(action.path("type").asString())) {
                String skill = action.path("skill").asString("");
                if (!ToolPolicy.knownSkills().contains(skill) || !policy.allowsSkill(skill)) {
                    messages.add(new LlmClient.Message("assistant", raw));
                    messages.add(new LlmClient.Message("user",
                            "observation: 技能不在当前意图白名单中，禁止执行：" + skill));
                    continue;
                }
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
                if (!ToolPolicy.knownTools().contains(tool) || !policy.allowsTool(tool)) {
                    messages.add(new LlmClient.Message("assistant", raw));
                    messages.add(new LlmClient.Message("user",
                            "observation: 工具不在当前意图白名单中，禁止执行：" + tool
                                    + "；请改用可用工具或输出 final 说明能力边界。"));
                    continue;
                }
                if ("add_cart_item".equals(tool)) {
                    String productId = String.valueOf(args.getOrDefault("product_id", ""));
                    if (productId.isBlank() || !allowedProductIds.contains(productId)) {
                        messages.add(new LlmClient.Message("assistant", raw));
                        messages.add(new LlmClient.Message("user",
                                "observation: 当前轮没有可加购的商品白名单 ID；禁止根据猜测、购物车或列表位置加购。"));
                        continue;
                    }
                }
                if (canceled.getAsBoolean()) {
                    throw new CancellationException("run canceled");
                }
                ThoughtStep thought = new ThoughtStep("step_" + (step + 1), "调用工具 " + tool,
                        "running", null, null, null, step + 1);
                emit.emit(thinking(run.runId(), thought));
                ToolRegistry.Observation observation = tools.execute(tool, args, account);
                rememberProducts(observation, allowedProductIds);
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
                localGuide(run, query, policy, allowedProductIds, emit);
                return;
            }
            emitText(run, finalText, emit);
            return;
        }
        emitText(run, "这个问题需要更多信息，可以再具体描述一下吗？", emit);
    }

    private record StreamAnswer(String raw, String finalText, boolean streamed) {
    }

    private StreamAnswer streamAnswer(AgentRun run, List<LlmClient.Message> messages,
                                      Set<String> allowedProductIds, BooleanSupplier canceled,
                                      Emitter emit) throws Exception {
        StringBuilder raw = new StringBuilder();
        StringBuilder prefix = new StringBuilder();
        StringBuilder finalText = new StringBuilder();
        boolean[] inFinal = {false};
        StreamTextFilter filter = new StreamTextFilter(List.copyOf(allowedProductIds), id -> {
            try {
                emit.emit(SSEEvent.block(run.runId(), Blocks.productRefs(List.of(id))));
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        },
                block -> {
                    try {
                        emit.emit(SSEEvent.block(run.runId(), block));
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                }, objectMapper);
        FinalForwarder forwarder = new FinalForwarder(run, finalText, emit, filter);
        try {
            llm.stream(llm.smallModel(), messages, 0.3, canceled, delta -> {
                raw.append(delta);
                if (forwarder.done()) {
                    return;
                }
                if (!inFinal[0]) {
                    prefix.append(delta);
                    int index = prefix.indexOf("<final>");
                    if (index >= 0) {
                        inFinal[0] = true;
                        try {
                            emitStatus(run, "answer", "正在生成回答", emit);
                        } catch (IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                        forwarder.forward(prefix.substring(index + 7));
                    }
                    return;
                }
                forwarder.forward(delta);
            });
        } catch (java.io.UncheckedIOException ex) {
            throw ex.getCause();
        }
        if (inFinal[0] && !forwarder.done()) {
            forwarder.forward("");
        }
        return new StreamAnswer(raw.toString(), finalText.toString(), inFinal[0]);
    }

    private final class FinalForwarder {
        private final AgentRun run;
        private final StringBuilder finalText;
        private final Emitter emit;
        private final StreamTextFilter filter;
        private final StringBuilder pending = new StringBuilder();
        private boolean done;

        private FinalForwarder(AgentRun run, StringBuilder finalText, Emitter emit,
                               StreamTextFilter filter) {
            this.run = run;
            this.finalText = finalText;
            this.emit = emit;
            this.filter = filter;
        }

        private boolean done() {
            return done;
        }

        private void forward(String delta) {
            if (done) {
                return;
            }
            pending.append(delta);
            String buffer = pending.toString();
            int close = buffer.indexOf("</final>");
            if (close >= 0) {
                done = true;
                String part = buffer.substring(0, close);
                pending.setLength(0);
                emitClean(part);
                return;
            }
            if (buffer.length() > 8) {
                String safe = buffer.substring(0, buffer.length() - 8);
                pending.setLength(0);
                pending.append(buffer.substring(buffer.length() - 8));
                emitClean(safe);
            }
        }

        private void emitClean(String part) {
            if (part.isEmpty()) {
                return;
            }
            String clean = filter.clean(part);
            if (clean.isEmpty()) {
                return;
            }
            finalText.append(clean);
            try {
                emit.emit(new SSEEvent("content_delta", run.runId(), null, null, null, null,
                        null, null, null, Blocks.text(clean), null, null, null, null));
                emit.emit(SSEEvent.delta("text_delta", run.runId(), clean));
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
    }

    private void rememberProducts(ToolRegistry.Observation observation,
                                  Set<String> allowedProductIds) {
        Object status = observation.result().get("relevance_status");
        boolean acceptable = status == null || "ok".equals(status) || "matched".equals(status);
        if (!acceptable) {
            return;
        }
        Object items = observation.result().get("items");
        if (items instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof ProductCard card) {
                    allowedProductIds.add(card.productId());
                } else if (item instanceof Map<?, ?> map && map.get("productId") != null) {
                    allowedProductIds.add(String.valueOf(map.get("productId")));
                }
            }
        }
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

    private void emitCanceled(AgentRun run, Emitter emit) throws IOException {
        emit.emit(SSEEvent.error(run.runId(), "canceled", "已停止生成"));
        emit.emit(SSEEvent.of("message_end", run.runId()));
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

    private String systemPrompt(PlannerService.Plan plan, ToolPolicy.Policy policy) {
        StringBuilder prompt = new StringBuilder();
        appendPrompt(prompt, PromptSeeder.MAIN_TEMPLATE);
        appendPrompt(prompt, PromptSeeder.TOOL_PROTOCOL);
        appendPrompt(prompt, PromptSeeder.FINAL_OUTPUT_RULES);
        if ("non_guide".equals(plan.route())) {
            appendPrompt(prompt, PromptSeeder.NON_GUIDE_FINAL_OUTPUT_RULES);
        }
        String intentPrompt = prompts.intentPrompt(plan.intent());
        if (!intentPrompt.isBlank()) {
            prompt.append(intentPrompt).append('\n');
        }
        prompt.append("当前 route=").append(plan.route())
                .append("，intent=").append(plan.intent())
                .append("，level=").append(plan.level())
                .append("，secondary_level=").append(plan.secondaryLevel()).append("。\n");
        prompt.append("可用工具：").append(policy.tools()).append('\n');
        prompt.append("可用 skill：").append(policy.skills()).append('\n');
        prompt.append("禁用能力：").append(policy.disabled()).append('\n');
        prompt.append("工具使用侧重：\n");
        for (String focus : policy.focus()) {
            prompt.append("- ").append(focus).append('\n');
        }
        prompt.append("""
                硬约束：
                - 只能调用“可用工具”列表中的工具；禁止调用“禁用能力”中的工具。
                - 需要的能力不在可用工具或 skill 中时，输出 <final>澄清或说明能力边界</final>。
                - search_products 返回 relevance_status=weak/no_match 时，不得把候选商品当作推荐或挂品。
                - 加购只能使用本轮回检索结果中的 product_id，不得猜测。
                工具/技能调用只输出 JSON；最终回答只输出 <final>回答</final>，不要在标签外输出正文。
                """);
        return prompt.toString();
    }

    private void appendPrompt(StringBuilder builder, String key) {
        String content = prompts.content(key);
        if (content != null && !content.isBlank()) {
            builder.append(content).append('\n');
        }
    }

    private static String navigationSkill(String lower) {
        if (lower.contains("订单")) {
            return "navigate_orders";
        }
        if (lower.contains("商品") || lower.contains("产品")) {
            return "navigate_products";
        }
        if (lower.contains("优惠") || lower.contains("券")) {
            return "coupon_help";
        }
        return "navigate_cart";
    }

    private static PlannerService.Plan heuristic(String route, String intent) {
        return new PlannerService.Plan(route, intent, "", "", "", "");
    }

    public static PlannerService.Plan plan(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (lower.contains("你好") || lower.contains("谢谢") || lower.contains("在吗")
                || lower.contains("再见")) {
            return heuristic("non_guide", "chitchat");
        }
        if (lower.contains("打开") || lower.contains("进入") || lower.contains("入口")
                || (lower.contains("去") && lower.contains("页"))) {
            return heuristic("non_guide", "navigation_service");
        }
        if (lower.contains("售后") || lower.contains("退款") || lower.contains("退货")
                || lower.contains("换货") || lower.contains("保修") || lower.contains("发票")
                || lower.contains("投诉")) {
            return heuristic("non_guide", "after_sales_service");
        }
        if (lower.contains("评价") || lower.contains("评论") || lower.contains("差评")) {
            return heuristic("non_guide", "review_service");
        }
        if (lower.contains("优惠券") || lower.contains("券") || lower.contains("促销")
                || lower.contains("活动")) {
            return heuristic("non_guide", "coupon_service");
        }
        if (lower.contains("订单") || lower.contains("支付") || lower.contains("付款")
                || lower.contains("取消") || lower.contains("收货") || lower.contains("物流")) {
            return heuristic("non_guide", "order_service");
        }
        if (lower.contains("购物车") || lower.contains("加购") || lower.contains("结算")
                || lower.contains("下单")) {
            return heuristic("non_guide", "cart_service");
        }
        if (lower.contains("账号") || lower.contains("登录") || lower.contains("注册")
                || lower.contains("地址") || lower.contains("会员")) {
            return heuristic("non_guide", "account_service");
        }
        if (lower.contains("对比") || lower.contains("比较") || lower.contains("选哪个")) {
            return heuristic("guide", "compare_decide");
        }
        if (lower.contains("搭配") || lower.contains("穿搭") || lower.contains("风格")) {
            return heuristic("guide", "outfit_styling");
        }
        if (lower.contains("场景") || lower.contains("清单") || lower.contains("一套")
                || lower.contains("全家")) {
            return heuristic("guide", "scene_solution");
        }
        if (lower.contains("预算") || lower.contains("以内") || lower.contains("不超过")
                || lower.contains("排除") || lower.contains("不要")) {
            return heuristic("guide", "category_shop_complex");
        }
        if (lower.contains("品牌") || lower.contains("旗舰店")) {
            return heuristic("guide", "category_shop_brand");
        }
        if (lower.matches(".*(iphone|ipad|macbook|mate|pro|max|ultra|\\d{3,}).*")) {
            return heuristic("guide", "product_deep");
        }
        if (lower.contains("好看") || lower.contains("ins") || lower.contains("ip")) {
            return heuristic("guide", "open_explore");
        }
        return heuristic("guide", "category_shop_no_brand");
    }

    private static boolean looksLikeMemoryReference(String query) {
        String value = query == null ? "" : query;
        for (String keyword : List.of("刚才", "上面", "前面", "那个", "这个", "第二个",
                "第一个", "第三个", "它", "继续")) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> followupFallback(PlannerService.Plan plan) {        if (!"guide".equals(plan.route())) {
            return List.of("查看我的订单", "看看购物车", "有什么优惠券");
        }
        return List.of("帮我对比这几款", "有没有更便宜的", "先加入购物车");
    }

    private Account accountOf(AgentRun run) {
        return accounts.findById(run.accountId()).orElseThrow(ApiException::unauthorized);
    }
}
