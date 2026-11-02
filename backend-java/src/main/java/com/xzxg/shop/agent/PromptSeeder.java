package com.xzxg.shop.agent;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the database prompt rows that the Go backend creates at startup. Content is
 * the runtime's default protocol text; richer per-intent defaults can be imported
 * later without changing the storage contract.
 */
@Component
public class PromptSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptSeeder.class);

    public static final String TOOL_PROTOCOL = "agent.prompt.tool_call_protocol";
    public static final String MAIN_TEMPLATE = "agent.prompt.main_template";
    public static final String FOLLOWUPS = "agent.prompt.followups";

    private final PromptRepository prompts;

    public PromptSeeder(PromptRepository prompts) {
        this.prompts = prompts;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            prompts.seedIfMissing(List.of(
                    new PromptRepository.PromptSeed("agent.prompt.route", "一级路由 Prompt",
                            "判断用户请求属于 guide（商品导购）还是 non_guide（购物车、订单、优惠、售后等确定性操作）。",
                            "guide/non_guide 路由"),
                    new PromptRepository.PromptSeed("agent.prompt.guide_intent", "导购细分 Prompt",
                            "在 guide 路由内识别商品推荐、对比、知识问答等细分意图。",
                            "导购意图识别"),
                    new PromptRepository.PromptSeed("agent.prompt.non_guide_intent", "非导购细分 Prompt",
                            "识别购物车、订单、支付、取消、收货、优惠券、评价等确定性意图。",
                            "非导购意图识别"),
                    new PromptRepository.PromptSeed(MAIN_TEMPLATE, "主 Agent 模板 Prompt",
                            "你是 xzxg-shop 的导购 Agent，回答要简洁、可执行，不编造商品或订单信息。",
                            "主 Agent 系统提示词"),
                    new PromptRepository.PromptSeed(TOOL_PROTOCOL, "工具调用协议 Prompt",
                            "只输出 JSON。工具调用：{\"type\":\"tool_call\",\"tool\":\"工具名\",\"arguments\":{}}；"
                                    + "技能调用：{\"type\":\"skill_call\",\"skill\":\"技能名\"}；"
                                    + "最终回答：{\"type\":\"final\",\"text\":\"回答\"}。",
                            "ReAct 调用协议"),
                    new PromptRepository.PromptSeed("agent.prompt.final_output_rules",
                            "最终回答规范 Prompt",
                            "用中文回答，先给结论再给理由；涉及价格、库存、订单状态时必须来自工具结果。",
                            "最终回答规范"),
                    new PromptRepository.PromptSeed(FOLLOWUPS, "追问生成 Prompt",
                            "生成三个简短、与当前问题相关的追问。",
                            "追问生成")
            ));
        } catch (RuntimeException ex) {
            // Mirrors the Go startup behaviour: prompt seeding is best-effort.
            log.warn("seed agent prompts skipped: {}", ex.getMessage());
        }
    }
}
