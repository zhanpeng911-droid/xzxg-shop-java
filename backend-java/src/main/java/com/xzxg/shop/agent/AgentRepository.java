package com.xzxg.shop.agent;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.common.Ids;
import com.xzxg.shop.common.JsonCodec;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.domain.AgentBlock;
import com.xzxg.shop.domain.AgentRun;
import com.xzxg.shop.domain.AgentSegment;
import com.xzxg.shop.domain.Attachment;
import com.xzxg.shop.domain.ChatSession;
import com.xzxg.shop.domain.ChatSessionDetail;
import com.xzxg.shop.domain.UserMessage;
import com.xzxg.shop.domain.UserMessageWithRuns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
public class AgentRepository {

    private static final String RUN_COLUMNS = """
            run_id, session_id, message_id, account_id, status, trace_id,
            COALESCE(content, ''), COALESCE(CAST(blocks_json AS CHAR), ''),
            COALESCE(CAST(followups_json AS CHAR), ''),
            COALESCE(CAST(segments_json AS CHAR), ''), created_at, updated_at
            """;

    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    public AgentRepository(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record CreateMessageResult(UserMessage message, boolean created) {
    }

    public record CreateRunResult(AgentRun run, boolean created) {
    }

    public ChatSession createSession(String accountId, String title) {
        LocalDateTime now = LocalDateTime.now();
        ChatSession session = new ChatSession(Ids.next("sess"), accountId,
                title == null ? "" : title, "", 0, GoTime.format(now),
                GoTime.format(null), GoTime.format(null), GoTime.format(now), GoTime.format(now));
        jdbc.update("""
                INSERT INTO chat_sessions (session_id, account_id, title, summary, message_count,
                                           last_message_at, created_at, updated_at)
                VALUES (?, ?, ?, '', 0, ?, ?, ?)
                """, session.sessionId(), accountId, session.title(), Timestamp.valueOf(now),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
        return session;
    }

    public Optional<ChatSession> getSession(String accountId, String sessionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT session_id, account_id, title, summary, message_count, last_message_at,
                           pinned_at, deleted_at, created_at, updated_at
                    FROM chat_sessions
                    WHERE session_id = ? AND account_id = ? AND deleted_at IS NULL
                    """, (rs, rowNum) -> mapSession(rs), sessionId, accountId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<ChatSession> listSessions(String accountId) {
        return jdbc.query("""
                SELECT session_id, account_id, title, summary, message_count, last_message_at,
                       pinned_at, deleted_at, created_at, updated_at
                FROM chat_sessions
                WHERE account_id = ? AND message_count > 0 AND deleted_at IS NULL
                ORDER BY CASE WHEN pinned_at IS NULL THEN 1 ELSE 0 END, pinned_at DESC,
                         COALESCE(last_message_at, created_at) DESC, created_at DESC
                """, (rs, rowNum) -> mapSession(rs), accountId);
    }

    public SearchResult searchSessions(String accountId, String keyword, PageRequest page) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            List<ChatSession> all = listSessions(accountId);
            int total = all.size();
            int start = page.offset();
            if (start >= total) {
                return new SearchResult(List.of(), total);
            }
            int end = Math.min(start + page.pageSize(), total);
            return new SearchResult(all.subList(start, end), total);
        }
        String like = "%" + trimmed + "%";
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT DISTINCT s.session_id
                    FROM chat_sessions s
                    LEFT JOIN user_messages m ON m.session_id = s.session_id
                        AND m.account_id = s.account_id
                    WHERE s.account_id = ? AND s.message_count > 0 AND s.deleted_at IS NULL
                      AND (s.title LIKE ? OR s.summary LIKE ? OR m.content LIKE ?)
                ) hits
                """, Integer.class, accountId, like, like, like);
        List<ChatSession> items = jdbc.query("""
                SELECT DISTINCT s.session_id, s.account_id, s.title, s.summary, s.message_count,
                       s.last_message_at, s.pinned_at, s.deleted_at, s.created_at, s.updated_at
                FROM chat_sessions s
                LEFT JOIN user_messages m ON m.session_id = s.session_id
                    AND m.account_id = s.account_id
                WHERE s.account_id = ? AND s.message_count > 0 AND s.deleted_at IS NULL
                  AND (s.title LIKE ? OR s.summary LIKE ? OR m.content LIKE ?)
                ORDER BY CASE WHEN s.pinned_at IS NULL THEN 1 ELSE 0 END, s.pinned_at DESC,
                         COALESCE(s.last_message_at, s.updated_at, s.created_at) DESC,
                         s.created_at DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> mapSession(rs), accountId, like, like, like,
                page.pageSize(), page.offset());
        return new SearchResult(items, total == null ? 0 : total);
    }

    public record SearchResult(List<ChatSession> items, int total) {
    }

    public Optional<ChatSession> updateSessionSummary(String accountId, String sessionId,
                                                      String title, String summary) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        sets.add("updated_at = ?");
        args.add(Timestamp.valueOf(LocalDateTime.now()));
        if (title != null && !title.isBlank()) {
            sets.add("title = ?");
            args.add(truncate(title.trim(), 128));
        }
        if (summary != null && !summary.isBlank()) {
            sets.add("summary = ?");
            args.add(truncate(summary.trim(), 500));
        }
        args.add(sessionId);
        args.add(accountId);
        int affected = jdbc.update("UPDATE chat_sessions SET " + String.join(", ", sets)
                + " WHERE session_id = ? AND account_id = ? AND deleted_at IS NULL", args.toArray());
        return affected == 0 ? Optional.empty() : getSession(accountId, sessionId);
    }

    public Optional<ChatSession> pinSession(String accountId, String sessionId, boolean pinned) {
        int affected = jdbc.update("""
                UPDATE chat_sessions SET pinned_at = ?, updated_at = ?
                WHERE session_id = ? AND account_id = ? AND deleted_at IS NULL
                """, pinned ? Timestamp.valueOf(LocalDateTime.now()) : null,
                Timestamp.valueOf(LocalDateTime.now()), sessionId, accountId);
        return affected == 0 ? Optional.empty() : getSession(accountId, sessionId);
    }

    public boolean deleteSession(String accountId, String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        return jdbc.update("""
                UPDATE chat_sessions SET deleted_at = ?, updated_at = ?
                WHERE session_id = ? AND account_id = ? AND deleted_at IS NULL
                """, Timestamp.valueOf(now), Timestamp.valueOf(now), sessionId, accountId) > 0;
    }

    public Optional<ChatSessionDetail> getSessionDetail(String accountId, String sessionId) {
        Optional<ChatSession> session = getSession(accountId, sessionId);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        List<UserMessageWithRuns> messages = jdbc.query("""
                SELECT message_id, session_id, account_id, client_message_id, content,
                       attachments_json, created_at
                FROM user_messages
                WHERE account_id = ? AND session_id = ?
                ORDER BY created_at, message_id
                """, (rs, rowNum) -> new UserMessageWithRuns(
                rs.getString("message_id"),
                rs.getString("session_id"),
                nullToEmpty(rs.getString("account_id")),
                rs.getString("client_message_id"),
                rs.getString("content"),
                json.readList(rs.getString("attachments_json"),
                        new TypeReference<List<Attachment>>() {
                        }),
                GoTime.format(timestamp(rs, "created_at")),
                listRunsByMessage(accountId, rs.getString("message_id"))),
                accountId, sessionId);
        return Optional.of(new ChatSessionDetail(session.get(), messages));
    }

    public CreateMessageResult createUserMessage(UserMessage input) {
        String messageId = Ids.next("msg");
        String clientMessageId = input.clientMessageId() == null || input.clientMessageId().isBlank()
                ? "server_" + messageId : input.clientMessageId();
        LocalDateTime now = LocalDateTime.now();
        String attachments = json.write(input.attachments() == null ? List.of() : input.attachments());
        try {
            jdbc.update("""
                    INSERT INTO user_messages (message_id, session_id, account_id, client_message_id,
                                               content, attachments_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, messageId, input.sessionId(), input.accountId(), clientMessageId,
                    input.content(), attachments, Timestamp.valueOf(now));
        } catch (DuplicateKeyException ex) {
            return findByClientMessageId(input.accountId(), input.sessionId(), clientMessageId)
                    .map(existing -> new CreateMessageResult(existing, false))
                    .orElseThrow(() -> ex);
        }
        UserMessage message = new UserMessage(messageId, input.sessionId(), input.accountId(),
                clientMessageId, input.content(),
                input.attachments() == null ? List.of() : input.attachments(),
                GoTime.format(now));
        String title = deriveSessionTitle(input.content());
        String summary = deriveSessionSummary(input.content());
        jdbc.update("""
                UPDATE chat_sessions
                SET title = CASE WHEN title = '' OR title = 'AI 导购' THEN ? ELSE title END,
                    summary = CASE WHEN summary IS NULL OR summary = '' THEN ? ELSE summary END,
                    message_count = message_count + 1, last_message_at = ?, updated_at = ?
                WHERE session_id = ? AND account_id = ?
                """, title, summary, Timestamp.valueOf(now), Timestamp.valueOf(now),
                input.sessionId(), input.accountId());
        return new CreateMessageResult(message, true);
    }

    private Optional<UserMessage> findByClientMessageId(String accountId, String sessionId,
                                                        String clientMessageId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT message_id, session_id, account_id, client_message_id, content,
                           attachments_json, created_at
                    FROM user_messages
                    WHERE account_id = ? AND session_id = ? AND client_message_id = ?
                    """, (rs, rowNum) -> new UserMessage(
                    rs.getString("message_id"),
                    rs.getString("session_id"),
                    nullToEmpty(rs.getString("account_id")),
                    rs.getString("client_message_id"),
                    rs.getString("content"),
                    json.readList(rs.getString("attachments_json"),
                            new TypeReference<List<Attachment>>() {
                            }),
                    GoTime.format(timestamp(rs, "created_at"))),
                    accountId, sessionId, clientMessageId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public CreateRunResult createRun(String accountId, String sessionId, String messageId) {
        LocalDateTime now = LocalDateTime.now();
        AgentRun run = new AgentRun(Ids.next("run"), sessionId, messageId, accountId, "running",
                Ids.next("trace"), "", GoTime.format(now), GoTime.format(now), "",
                List.of(), List.of(), List.of());
        try {
            jdbc.update("""
                    INSERT INTO agent_runs (run_id, session_id, message_id, account_id, status,
                                            trace_id, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'running', ?, ?, ?)
                    """, run.runId(), sessionId, messageId, accountId, run.traceId(),
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
        } catch (DuplicateKeyException ex) {
            return findRunByMessage(accountId, messageId)
                    .map(existing -> new CreateRunResult(existing, false))
                    .orElseThrow(() -> ex);
        }
        return new CreateRunResult(run, true);
    }

    public Optional<AgentRun> getRun(String runId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + RUN_COLUMNS + " FROM agent_runs WHERE run_id = ?",
                    (rs, rowNum) -> mapRun(rs), runId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<AgentRun> findRunByMessage(String accountId, String messageId) {
        List<AgentRun> runs = jdbc.query("SELECT " + RUN_COLUMNS + """
                 FROM agent_runs
                WHERE account_id = ? AND message_id = ?
                ORDER BY created_at, run_id
                LIMIT 1
                """, (rs, rowNum) -> mapRun(rs), accountId, messageId);
        return runs.stream().findFirst();
    }

    public List<AgentRun> listRunsByMessage(String accountId, String messageId) {
        return jdbc.query("SELECT " + RUN_COLUMNS + """
                 FROM agent_runs
                WHERE account_id = ? AND message_id = ?
                ORDER BY created_at, run_id
                """, (rs, rowNum) -> mapRun(rs), accountId, messageId);
    }

    public Optional<AgentRun> updateRunStatus(String accountId, String runId, String status) {
        String condition = switch (status) {
            case "completed", "failed", "canceled" -> " AND status = 'running'";
            default -> "";
        };
        int affected = jdbc.update("UPDATE agent_runs SET status = ?, updated_at = ?"
                        + " WHERE run_id = ? AND account_id = ?" + condition,
                status, Timestamp.valueOf(LocalDateTime.now()), runId, accountId);
        return affected == 0 ? Optional.empty() : getRun(runId);
    }

    public boolean updateRunResult(String accountId, String runId, String content,
                                   List<AgentBlock> blocks, List<String> followups,
                                   List<AgentSegment> segments) {
        return jdbc.update("""
                UPDATE agent_runs
                SET content = ?, blocks_json = CAST(? AS JSON), followups_json = CAST(? AS JSON),
                    segments_json = CAST(? AS JSON), updated_at = ?
                WHERE run_id = ? AND account_id = ?
                """, content, json.write(blocks == null ? List.of() : blocks),
                json.write(followups == null ? List.of() : followups),
                json.write(segments == null ? List.of() : segments),
                Timestamp.valueOf(LocalDateTime.now()), runId, accountId) > 0;
    }

    public boolean isRunCanceled(String runId) {
        return getRun(runId).map(run -> "canceled".equals(run.status())).orElse(false);
    }

    public List<AgentRun> listRunsPage(PageRequest page) {
        return jdbc.query("""
                SELECT ar.run_id, ar.session_id, ar.message_id, ar.account_id, ar.status, ar.trace_id,
                       COALESCE(um.content, '') AS query_title,
                       COALESCE(ar.content, ''), COALESCE(CAST(ar.blocks_json AS CHAR), ''),
                       COALESCE(CAST(ar.followups_json AS CHAR), ''),
                       COALESCE(CAST(ar.segments_json AS CHAR), ''), ar.created_at, ar.updated_at
                FROM agent_runs ar
                LEFT JOIN user_messages um ON um.message_id = ar.message_id
                    AND um.account_id = ar.account_id
                ORDER BY ar.created_at DESC, ar.run_id DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> mapRunWithQueryTitle(rs), page.pageSize(), page.offset());
    }

    public int countRuns() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Integer.class);
        return total == null ? 0 : total;
    }

    public void recordTrace(String runId, String traceId, String accountId, String stage,
                            String eventType, String model, String status, long durationMs,
                            String error, String metadataJson) {
        jdbc.update("""
                INSERT INTO agent_trace_events (trace_event_id, run_id, trace_id, account_id, stage,
                                                event_type, model, status, duration_ms, error,
                                                metadata_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.next("trc"), runId, traceId, accountId, stage, eventType, model, status,
                durationMs, error == null ? "" : error,
                metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public List<com.xzxg.shop.domain.AgentTraceEvent> listTrace(String accountId, String runId) {
        return jdbc.query("""
                SELECT trace_event_id, run_id, trace_id, account_id, stage, event_type, model,
                       status, duration_ms, COALESCE(error, '') AS error,
                       COALESCE(CAST(metadata_json AS CHAR), '') AS metadata_json, created_at
                FROM agent_trace_events
                WHERE run_id = ? AND account_id = ?
                ORDER BY created_at, trace_event_id
                """, (rs, rowNum) -> mapTrace(rs), runId, accountId);
    }

    public List<com.xzxg.shop.domain.AgentTraceEvent> listTraceByRun(String runId) {
        return jdbc.query("""
                SELECT trace_event_id, run_id, trace_id, account_id, stage, event_type, model,
                       status, duration_ms, COALESCE(error, '') AS error,
                       COALESCE(CAST(metadata_json AS CHAR), '') AS metadata_json, created_at
                FROM agent_trace_events
                WHERE run_id = ?
                ORDER BY created_at, trace_event_id
                """, (rs, rowNum) -> mapTrace(rs), runId);
    }

    public List<MemoryRecord> recentConversations(String accountId, String sessionId, int limit) {
        int effective = limit <= 0 || limit > 20 ? 5 : limit;
        return jdbc.query("""
                SELECT m.message_id, m.content AS user_query,
                       COALESCE(r.run_id, '') AS run_id, COALESCE(r.content, '') AS final_answer,
                       m.created_at
                FROM user_messages m
                LEFT JOIN agent_runs r ON r.message_id = m.message_id
                    AND r.account_id = m.account_id AND r.status = 'completed'
                WHERE m.account_id = ? AND m.session_id = ? AND m.content <> ''
                ORDER BY m.created_at DESC, m.message_id DESC
                LIMIT ?
                """, (rs, rowNum) -> new MemoryRecord(
                rs.getString("run_id"), rs.getString("user_query"),
                rs.getString("final_answer")), accountId, sessionId, effective);
    }

    public record MemoryRecord(String runId, String userQuery, String finalAnswer) {
    }

    private ChatSession mapSession(ResultSet rs) throws SQLException {
        return new ChatSession(
                rs.getString("session_id"),
                nullToEmpty(rs.getString("account_id")),
                rs.getString("title"),
                nullToEmpty(rs.getString("summary")),
                rs.getInt("message_count"),
                GoTime.format(timestamp(rs, "last_message_at")),
                GoTime.format(timestamp(rs, "pinned_at")),
                GoTime.format(timestamp(rs, "deleted_at")),
                GoTime.format(timestamp(rs, "created_at")),
                GoTime.format(timestamp(rs, "updated_at")));
    }

    private AgentRun mapRun(ResultSet rs) throws SQLException {
        return buildRun(rs, "", 7);
    }

    private AgentRun mapRunWithQueryTitle(ResultSet rs) throws SQLException {
        return buildRun(rs, rs.getString(7), 8);
    }

    private AgentRun buildRun(ResultSet rs, String queryTitle, int contentIndex) throws SQLException {
        String blocksJson = rs.getString(contentIndex + 1);
        String followupsJson = rs.getString(contentIndex + 2);
        String segmentsJson = rs.getString(contentIndex + 3);
        return new AgentRun(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                nullToEmpty(rs.getString(4)),
                rs.getString(5),
                rs.getString(6),
                queryTitle,
                GoTime.format(timestamp(rs, "created_at")),
                GoTime.format(timestamp(rs, "updated_at")),
                rs.getString(contentIndex),
                json.readList(blocksJson, new TypeReference<List<AgentBlock>>() {
                }),
                json.readList(followupsJson, new TypeReference<List<String>>() {
                }),
                json.readList(segmentsJson, new TypeReference<List<AgentSegment>>() {
                }));
    }

    private com.xzxg.shop.domain.AgentTraceEvent mapTrace(ResultSet rs) throws SQLException {
        return new com.xzxg.shop.domain.AgentTraceEvent(
                rs.getString("trace_event_id"),
                rs.getString("run_id"),
                rs.getString("trace_id"),
                nullToEmpty(rs.getString("account_id")),
                rs.getString("stage"),
                rs.getString("event_type"),
                nullToEmpty(rs.getString("model")),
                rs.getString("status"),
                rs.getLong("duration_ms"),
                nullToEmpty(rs.getString("error")),
                nullToEmpty(rs.getString("metadata_json")),
                GoTime.format(timestamp(rs, "created_at")));
    }

    public static String deriveSessionTitle(String content) {
        String cleaned = content == null ? "" : content.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return "AI 导购";
        }
        cleaned = cleaned.replace("，", "").replace("。", "").replace("？", "")
                .replace("！", "").replace(",", "").replace(".", "").replace("?", "")
                .replace("!", "").replace("我想", "").replace("帮我", "")
                .replace("推荐", "").trim();
        if (cleaned.isEmpty()) {
            return "导购咨询";
        }
        return truncate(cleaned, 8);
    }

    public static String deriveSessionSummary(String content) {
        String cleaned = content == null ? "" : content.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return "";
        }
        return truncate("用户咨询：" + cleaned, 120);
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String truncate(String value, int maxRunes) {
        String trimmed = value == null ? "" : value;
        if (trimmed.codePointCount(0, trimmed.length()) <= maxRunes) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, maxRunes));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
