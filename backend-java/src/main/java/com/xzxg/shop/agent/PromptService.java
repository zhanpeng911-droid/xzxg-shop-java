package com.xzxg.shop.agent;

import org.springframework.stereotype.Service;

/** Prompt resolution: DB active/published content first, Go resource default second. */
@Service
public class PromptService {

    private final PromptRepository prompts;
    private final PromptCatalog catalog;

    public PromptService(PromptRepository prompts, PromptCatalog catalog) {
        this.prompts = prompts;
        this.catalog = catalog;
    }

    public String content(String key) {
        return prompts.active(key)
                .map(value -> value.content() == null ? "" : value.content())
                .filter(value -> !value.isBlank())
                .orElseGet(() -> {
                    String fallback = catalog.defaultFor(key);
                    return fallback == null ? "" : fallback;
                });
    }

    public String intentPrompt(String intent) {
        if (intent == null || intent.isBlank()) {
            return "";
        }
        return content("agent.prompt.intent." + intent.trim());
    }

    public String defaultFor(String key) {
        return catalog.defaultFor(key);
    }
}
