package com.xzxg.shop.agent;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds every Go default prompt and upgrades rows that still contain one of the
 * earlier simplified defaults. User-customized rows are preserved.
 */
@Component
public class PromptSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptSeeder.class);

    public static final String MAIN_TEMPLATE = "agent.prompt.main_template";
    public static final String TOOL_PROTOCOL = "agent.prompt.tool_call_protocol";
    public static final String FOLLOWUPS = "agent.prompt.followups";
    public static final String ROUTE = "agent.prompt.route";
    public static final String GUIDE_INTENT = "agent.prompt.guide_intent";
    public static final String NON_GUIDE_INTENT = "agent.prompt.non_guide_intent";
    public static final String FINAL_OUTPUT_RULES = "agent.prompt.final_output_rules";
    public static final String NON_GUIDE_FINAL_OUTPUT_RULES =
            "agent.prompt.non_guide_final_output_rules";
    public static final String INTENT_TOOL_POLICY = "agent.prompt.intent_tool_policy";
    public static final String MEMORY_RETRIEVAL = "agent.prompt.memory_retrieval";
    public static final String SESSION_SUMMARY = "agent.prompt.session_summary";
    public static final String GUIDE_SUGGESTIONS = "agent.prompt.guide_suggestions";

    private final PromptRepository prompts;
    private final PromptCatalog catalog;

    public PromptSeeder(PromptRepository prompts, PromptCatalog catalog) {
        this.prompts = prompts;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (Map.Entry<String, PromptRepository.PromptSeed> entry
                    : catalog.allSeeds().entrySet()) {
                prompts.seedOrUpgrade(entry.getValue(),
                        catalog.legacyDefaults(entry.getKey()));
            }
            log.info("agent prompts seeded/upgraded: {}", catalog.keys().size());
        } catch (RuntimeException ex) {
            // Mirrors the Go startup behaviour: prompt seeding is best-effort.
            log.warn("seed agent prompts skipped: {}", ex.getMessage());
        }
    }
}
