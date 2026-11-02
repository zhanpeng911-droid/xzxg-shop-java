package com.xzxg.shop.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Legacy simplified prompt rows are upgraded; custom content is preserved. */
@SpringBootTest
@ActiveProfiles("mysql-it")
class PromptUpgradeIT {

    private static final String KEY = "agent.prompt.test_upgrade";

    @Autowired
    private PromptRepository prompts;

    @Autowired
    private PromptService promptService;

    @Autowired
    private PromptCatalog catalog;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void upgradesLegacyDefaultButKeepsUserCustomContent() {
        jdbc.update("DELETE FROM agent_prompts WHERE prompt_key = ?", KEY);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO agent_prompts (prompt_id, prompt_key, title, content, status, version,
                                           description, created_by, published_at, created_at,
                                           updated_at)
                VALUES ('prm_test_legacy', ?, 'test', 'legacy-default', 'active', 1, '', 'system',
                        ?, ?, ?)
                """, KEY, Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));

        PromptRepository.PromptSeed seed = new PromptRepository.PromptSeed(KEY, "test",
                "full-default", "desc");
        prompts.seedOrUpgrade(seed, List.of("legacy-default"));

        AgentPromptSnapshot upgraded = active();
        assertThat(upgraded.content()).isEqualTo("full-default");
        assertThat(upgraded.version()).isEqualTo(2);

        jdbc.update("UPDATE agent_prompts SET content = 'user-custom' WHERE prompt_key = ?"
                + " AND status = 'active'", KEY);
        prompts.seedOrUpgrade(seed, List.of("legacy-default"));

        AgentPromptSnapshot preserved = active();
        assertThat(preserved.content()).isEqualTo("user-custom");
        assertThat(preserved.version()).isEqualTo(2);
        jdbc.update("DELETE FROM agent_prompts WHERE prompt_key = ?", KEY);
    }

    @Test
    void databaseActivePromptTakesPriorityOverResourceDefault() {
        String key = "agent.prompt.test_priority";
        jdbc.update("DELETE FROM agent_prompts WHERE prompt_key = ?", key);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO agent_prompts (prompt_id, prompt_key, title, content, status, version,
                                           description, created_by, published_at, created_at,
                                           updated_at)
                VALUES ('prm_test_priority', ?, 'test', 'db-priority', 'active', 1, '', 'system',
                        ?, ?, ?)
                """, key, Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));

        assertThat(promptService.content(key)).isEqualTo("db-priority");
        jdbc.update("DELETE FROM agent_prompts WHERE prompt_key = ?", key);
        assertThat(promptService.content(key)).isEmpty();
    }

    private AgentPromptSnapshot active() {
        return jdbc.queryForObject("""
                SELECT content, version FROM agent_prompts
                WHERE prompt_key = ? AND status = 'active'
                ORDER BY version DESC LIMIT 1
                """, (rs, rowNum) -> new AgentPromptSnapshot(
                rs.getString("content"), rs.getInt("version")), KEY);
    }

    private record AgentPromptSnapshot(String content, int version) {
    }
}
