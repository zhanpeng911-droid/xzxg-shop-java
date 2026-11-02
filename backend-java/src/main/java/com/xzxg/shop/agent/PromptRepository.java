package com.xzxg.shop.agent;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.common.Ids;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.domain.AgentPrompt;
import com.xzxg.shop.domain.AgentPromptPublishRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PromptRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PromptRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public void seedIfMissing(List<PromptSeed> defaults) {
        for (PromptSeed seed : defaults) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_prompts WHERE prompt_key = ?", Integer.class,
                    seed.key());
            if (count != null && count > 0) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("""
                    INSERT INTO agent_prompts (prompt_id, prompt_key, title, content, status,
                                               version, description, created_by, published_at,
                                               created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'active', 1, ?, 'system', ?, ?, ?)
                    """, Ids.next("prm"), seed.key(), seed.title(), seed.content(),
                    seed.description(), Timestamp.valueOf(now), Timestamp.valueOf(now),
                    Timestamp.valueOf(now));
        }
    }

    public record PromptSeed(String key, String title, String content, String description) {
    }

    public PageResponse<AgentPrompt> listLatest(PageRequest page) {
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_prompts p
                JOIN (SELECT prompt_key, MAX(version) AS version FROM agent_prompts GROUP BY prompt_key)
                    latest ON latest.prompt_key = p.prompt_key AND latest.version = p.version
                """, Integer.class);
        List<AgentPrompt> items = jdbc.query("""
                SELECT p.prompt_id, p.prompt_key, p.title, p.content, p.status, p.version,
                       COALESCE(p.description, '') AS description, p.created_by, p.published_at,
                       p.created_at, p.updated_at
                FROM agent_prompts p
                JOIN (SELECT prompt_key, MAX(version) AS version FROM agent_prompts GROUP BY prompt_key)
                    latest ON latest.prompt_key = p.prompt_key AND latest.version = p.version
                ORDER BY p.prompt_key
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> map(rs), page.pageSize(), page.offset());
        return new PageResponse<>(items, page.page(), page.pageSize(), total == null ? 0 : total);
    }

    public Optional<AgentPrompt> active(String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT prompt_id, prompt_key, title, content, status, version,
                           COALESCE(description, '') AS description, created_by, published_at,
                           created_at, updated_at
                    FROM agent_prompts
                    WHERE prompt_key = ? AND status = 'active'
                    ORDER BY version DESC
                    LIMIT 1
                    """, (rs, rowNum) -> map(rs), key));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public AgentPrompt saveDraft(String key, String title, String content, String description,
                                 String createdBy) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("prompt key is empty");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt content is empty");
        }
        Integer latest = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM agent_prompts WHERE prompt_key = ?",
                Integer.class, key);
        int version = (latest == null ? 0 : latest) + 1;
        LocalDateTime now = LocalDateTime.now();
        AgentPrompt prompt = new AgentPrompt(Ids.next("prm"), key,
                title == null || title.isBlank() ? key : title, content, "draft", version,
                description == null ? "" : description, createdBy, GoTime.format(null),
                GoTime.format(now), GoTime.format(now));
        jdbc.update("""
                INSERT INTO agent_prompts (prompt_id, prompt_key, title, content, status, version,
                                           description, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?)
                """, prompt.promptId(), key, prompt.title(), content, version,
                prompt.description(), createdBy, Timestamp.valueOf(now), Timestamp.valueOf(now));
        return prompt;
    }

    public record PublishResult(AgentPrompt prompt, AgentPromptPublishRecord record) {
    }

    public PublishResult publish(String key, String publishedBy, String target) {
        PublishResult result = transactions.execute(status -> {
            List<AgentPrompt> latest = jdbc.query("""
                    SELECT prompt_id, prompt_key, title, content, status, version,
                           COALESCE(description, '') AS description, created_by, published_at,
                           created_at, updated_at
                    FROM agent_prompts WHERE prompt_key = ?
                    ORDER BY version DESC LIMIT 1
                    """, (rs, rowNum) -> map(rs), key);
            if (latest.isEmpty()) {
                throw new IllegalArgumentException("prompt not found");
            }
            AgentPrompt prompt = latest.get(0);
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("""
                    UPDATE agent_prompts SET status = 'archived', updated_at = ?
                    WHERE prompt_key = ? AND status = 'active'
                    """, Timestamp.valueOf(now), key);
            jdbc.update("""
                    UPDATE agent_prompts SET status = 'active', published_at = ?, updated_at = ?
                    WHERE prompt_id = ?
                    """, Timestamp.valueOf(now), Timestamp.valueOf(now), prompt.promptId());
            AgentPromptPublishRecord record = new AgentPromptPublishRecord(Ids.next("prmpub"),
                    key, prompt.promptId(), prompt.version(), publishedBy,
                    target == null || target.isBlank() ? "database" : target,
                    GoTime.format(now));
            jdbc.update("""
                    INSERT INTO agent_prompt_publish_records (record_id, prompt_key, prompt_id,
                                                              version, published_by, nacos_data_id,
                                                              created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, record.recordId(), key, prompt.promptId(), prompt.version(),
                    publishedBy, record.publishTarget(), Timestamp.valueOf(now));
            AgentPrompt published = new AgentPrompt(prompt.promptId(), prompt.promptKey(),
                    prompt.title(), prompt.content(), "active", prompt.version(),
                    prompt.description(), prompt.createdBy(), GoTime.format(now),
                    prompt.createdAt(), GoTime.format(now));
            return new PublishResult(published, record);
        });
        return result;
    }

    public List<AgentPromptPublishRecord> publishRecords(String key, int limit) {
        int effective = limit <= 0 || limit > 100 ? 20 : limit;
        String where = key == null || key.isBlank() ? "" : " WHERE prompt_key = ?";
        String sql = """
                SELECT record_id, prompt_key, prompt_id, version, published_by, nacos_data_id,
                       created_at
                FROM agent_prompt_publish_records
                """ + where + " ORDER BY created_at DESC, record_id DESC LIMIT ?";
        if (key == null || key.isBlank()) {
            return jdbc.query(sql, (rs, rowNum) -> mapRecord(rs), effective);
        }
        return jdbc.query(sql, (rs, rowNum) -> mapRecord(rs), key, effective);
    }

    private static AgentPrompt map(ResultSet rs) throws SQLException {
        return new AgentPrompt(
                rs.getString("prompt_id"),
                rs.getString("prompt_key"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getInt("version"),
                nullToEmpty(rs.getString("description")),
                nullToEmpty(rs.getString("created_by")),
                GoTime.format(timestamp(rs, "published_at")),
                GoTime.format(timestamp(rs, "created_at")),
                GoTime.format(timestamp(rs, "updated_at")));
    }

    private static AgentPromptPublishRecord mapRecord(ResultSet rs) throws SQLException {
        return new AgentPromptPublishRecord(
                rs.getString("record_id"),
                rs.getString("prompt_key"),
                rs.getString("prompt_id"),
                rs.getInt("version"),
                nullToEmpty(rs.getString("published_by")),
                rs.getString("nacos_data_id"),
                GoTime.format(timestamp(rs, "created_at")));
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
