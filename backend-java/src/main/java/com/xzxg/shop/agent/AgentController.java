package com.xzxg.shop.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.config.ShopProperties;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.AgentBlock;
import com.xzxg.shop.domain.AgentRun;
import com.xzxg.shop.domain.AgentSegment;
import com.xzxg.shop.domain.AgentTraceEvent;
import com.xzxg.shop.domain.Attachment;
import com.xzxg.shop.domain.ChatSession;
import com.xzxg.shop.domain.ChatSessionDetail;
import com.xzxg.shop.domain.SSEEvent;
import com.xzxg.shop.domain.ThoughtStep;
import com.xzxg.shop.domain.UserMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
public class AgentController {

    private final AgentRepository repository;
    private final AgentRuntime runtime;
    private final MemoryService memory;
    private final ObjectMapper objectMapper;
    private final long heartbeatMs;

    public AgentController(AgentRepository repository, AgentRuntime runtime, MemoryService memory,
                           ObjectMapper objectMapper, ShopProperties properties) {
        this.repository = repository;
        this.runtime = runtime;
        this.memory = memory;
        this.objectMapper = objectMapper;
        this.heartbeatMs = Math.max(200, properties.getAgent().getSseHeartbeatMs());
    }

    public record CreateSessionRequest(String title) {
    }

    public record UpdateSessionRequest(String title, String summary) {
    }

    public record PinRequest(boolean pinned) {
    }

    public record MessageRequest(
            @JsonProperty("client_message_id") String clientMessageId,
            String content,
            List<Attachment> attachments) {
    }

    @GetMapping("/api/v1/agent/sessions")
    public PageResponse<ChatSession> listSessions(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        Account user = requireUser(request);
        PageRequest pageRequest = PageRequest.of(page, pageSize);
        List<ChatSession> all = repository.listSessions(user.accountId());
        return paginate(all, pageRequest);
    }

    @GetMapping("/api/v1/agent/sessions/search")
    public PageResponse<ChatSession> searchSessions(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        Account user = requireUser(request);
        PageRequest pageRequest = PageRequest.of(page, pageSize);
        AgentRepository.SearchResult result = repository.searchSessions(user.accountId(),
                query, pageRequest);
        return new PageResponse<>(result.items(), pageRequest.page(), pageRequest.pageSize(),
                result.total());
    }

    @PostMapping("/api/v1/agent/sessions")
    public ChatSession createSession(@RequestBody(required = false) CreateSessionRequest body,
                                     HttpServletRequest request) {
        Account user = requireUser(request);
        String title = body == null || body.title() == null || body.title().isBlank()
                ? "AI 导购" : body.title();
        return repository.createSession(user.accountId(), title);
    }

    @GetMapping("/api/v1/agent/sessions/{sessionId}")
    public ChatSessionDetail session(@PathVariable String sessionId, HttpServletRequest request) {
        Account user = requireUser(request);
        return repository.getSessionDetail(user.accountId(), sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "session_not_found", "会话不存在"));
    }

    @PatchMapping("/api/v1/agent/sessions/{sessionId}")
    public ChatSession updateSession(@PathVariable String sessionId,
                                     @RequestBody(required = false) UpdateSessionRequest body,
                                     HttpServletRequest request) {
        Account user = requireUser(request);
        if (body == null) {
            throw badRequestBody();
        }
        return repository.updateSessionSummary(user.accountId(), sessionId, body.title(),
                        body.summary())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "session_not_found", "会话不存在"));
    }

    @DeleteMapping("/api/v1/agent/sessions/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId,
                                             HttpServletRequest request) {
        Account user = requireUser(request);
        if (!repository.deleteSession(user.accountId(), sessionId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "session_not_found", "会话不存在");
        }
        return Map.of("session_id", sessionId, "status", "deleted");
    }

    @PostMapping("/api/v1/agent/sessions/{sessionAction}")
    public ChatSession sessionAction(@PathVariable String sessionAction,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        Account user = requireUser(request);
        int colon = sessionAction.indexOf(':');
        if (colon <= 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "接口不存在");
        }
        String sessionId = sessionAction.substring(0, colon);
        String action = sessionAction.substring(colon + 1);
        if ("pin".equals(action)) {
            boolean pinned = body != null && Boolean.parseBoolean(
                    String.valueOf(body.getOrDefault("pinned", false)));
            return repository.pinSession(user.accountId(), sessionId, pinned)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "session_not_found", "会话不存在"));
        }
        if ("summarize".equals(action)) {
            ChatSessionDetail detail = repository.getSessionDetail(user.accountId(), sessionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "session_not_found", "会话不存在"));
            String summary = summarize(detail);
            String title = shortTitle(summary);
            return repository.updateSessionSummary(user.accountId(), sessionId, title, summary)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "session_not_found", "会话不存在"));
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "接口不存在");
    }

    @PostMapping("/api/v1/agent/sessions/{sessionId}/messages:stream")
    public void stream(@PathVariable String sessionId,
                       @RequestBody(required = false) MessageRequest body,
                       HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Account user = requireUser(request);
        repository.getSession(user.accountId(), sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "session_not_found", "会话不存在"));
        if (body == null) {
            throw badRequestBody();
        }
        if (body.content() == null || body.content().trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_content", "消息内容不能为空");
        }
        AgentRepository.CreateMessageResult created = repository.createUserMessage(
                new UserMessage(null, sessionId, user.accountId(), body.clientMessageId(),
                        body.content(), body.attachments() == null ? List.of() : body.attachments(),
                        GoTime.format(null)));
        AgentRepository.CreateRunResult runResult = repository.createRun(user.accountId(),
                sessionId, created.message().messageId());

        response.setContentType("text/event-stream; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        PrintWriter writer = response.getWriter();

        if (!created.created() || !runResult.created()) {
            writeSnapshot(writer, runResult.run(), "duplicate_message",
                    "请求已提交过，已返回已有执行记录");
            return;
        }

        List<AgentBlock> blocks = new ArrayList<>();
        List<String> followups = new ArrayList<>();
        List<AgentSegment> segments = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        StringBuilder currentText = new StringBuilder();
        Object lock = new Object();
        AtomicBoolean clientGone = new AtomicBoolean(false);
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            synchronized (lock) {
                if (clientGone.get()) {
                    return;
                }
                try {
                    writer.write(": ping\n\n");
                    writer.flush();
                    if (writer.checkError()) {
                        clientGone.set(true);
                    }
                } catch (RuntimeException ex) {
                    clientGone.set(true);
                }
            }
        }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);

        AgentRuntime.Emitter emitter = event -> {
            synchronized (lock) {
                if (clientGone.get()) {
                    throw new IOException("sse client disconnected");
                }
                accumulate(event, content, currentText, blocks, followups, segments);
                writer.write("event: " + event.type() + "\n");
                writer.write("data: " + objectMapper.writeValueAsString(event) + "\n\n");
                writer.flush();
                if (writer.checkError()) {
                    clientGone.set(true);
                    throw new IOException("sse client disconnected");
                }
            }
        };

        try {
            runtime.stream(runResult.run(), created.message(), emitter,
                    () -> repository.isRunCanceled(runResult.run().runId()) || clientGone.get());
        } catch (IOException ex) {
            repository.updateRunStatus(user.accountId(), runResult.run().runId(), "canceled");
            return;
        } catch (RuntimeException ex) {
            repository.updateRunStatus(user.accountId(), runResult.run().runId(), "failed");
            try {
                emitter.emit(SSEEvent.error(runResult.run().runId(), "internal_error",
                        "导购服务暂时不可用"));
                emitter.emit(SSEEvent.of("message_end", runResult.run().runId()));
            } catch (IOException ignored) {
                // client already gone
            }
            return;
        } finally {
            heartbeat.shutdownNow();
        }
        flushText(currentText, segments);
        repository.updateRunResult(user.accountId(), runResult.run().runId(),
                content.toString().trim(), blocks, followups, segments);
        if (clientGone.get()) {
            repository.updateRunStatus(user.accountId(), runResult.run().runId(), "canceled");
        } else if (!repository.isRunCanceled(runResult.run().runId())) {
            repository.updateRunStatus(user.accountId(), runResult.run().runId(), "completed");
            if (!skipSessionSummary(blocks)) {
                memory.updateSessionSummary(runResult.run(), created.message(),
                        content.toString().trim());
            }
        }
    }

    private static boolean skipSessionSummary(List<AgentBlock> blocks) {
        for (AgentBlock block : blocks) {
            if ("cart_state".equals(block.type()) || "order_summary".equals(block.type())) {
                return true;
            }
            if ("warning".equals(block.type())
                    && ("unsafe_request".equals(block.code())
                    || "risk_account".equals(block.code())
                    || "vlm_not_configured".equals(block.code()))) {
                return true;
            }
        }
        return false;
    }

    @PostMapping("/api/v1/agent/runs/{runAction}")
    public Map<String, String> runAction(@PathVariable String runAction,
                                         HttpServletRequest request) {
        Account user = requireUser(request);
        int colon = runAction.indexOf(':');
        if (colon <= 0 || !"cancel".equals(runAction.substring(colon + 1))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "接口不存在");
        }
        AgentRun run = repository.updateRunStatus(user.accountId(), runAction.substring(0, colon),
                        "canceled")
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "run_not_found", "执行不存在"));
        return Map.of("run_id", run.runId(), "status", run.status());
    }

    @GetMapping("/api/v1/agent/runs/{runId}/trace")
    public Map<String, List<AgentTraceEvent>> runTrace(@PathVariable String runId,
                                                       HttpServletRequest request) {
        Account user = requireUser(request);
        return Map.of("items", repository.listTrace(user.accountId(), runId));
    }

    private static void accumulate(SSEEvent event, StringBuilder content,
                                   StringBuilder currentText, List<AgentBlock> blocks,
                                   List<String> followups, List<AgentSegment> segments) {
        switch (event.type()) {
            case "text_delta" -> {
                content.append(event.delta());
                currentText.append(event.delta());
            }
            case "content_delta" -> {
                if (event.part() != null && !"text".equals(event.part().type())) {
                    flushText(currentText, segments);
                    blocks.add(event.part());
                    segments.add(new AgentSegment("block", null, event.part(), null));
                }
            }
            case "block_delta" -> {
                flushText(currentText, segments);
                if (event.block() != null) {
                    blocks.add(event.block());
                    segments.add(new AgentSegment("block", null, event.block(), null));
                }
            }
            case "thinking_delta" -> {
                if (event.step() != null) {
                    upsertThinking(segments, event.step());
                }
            }
            case "followups" -> {
                if (event.questions() != null) {
                    followups.clear();
                    followups.addAll(event.questions());
                }
            }
            default -> {
            }
        }
    }

    private static void flushText(StringBuilder currentText, List<AgentSegment> segments) {
        String text = currentText.toString().trim();
        if (!text.isEmpty()) {
            segments.add(new AgentSegment("text", text, null, null));
        }
        currentText.setLength(0);
    }

    private static void upsertThinking(List<AgentSegment> segments, ThoughtStep step) {
        if (step.id() == null || step.id().isEmpty()) {
            return;
        }
        for (int i = 0; i < segments.size(); i++) {
            AgentSegment segment = segments.get(i);
            if ("thinking".equals(segment.type()) && segment.thought() != null
                    && step.id().equals(segment.thought().id())) {
                segments.set(i, new AgentSegment("thinking", null, null, step));
                return;
            }
        }
        segments.add(new AgentSegment("thinking", null, null, step));
    }

    private void writeSnapshot(PrintWriter writer, AgentRun run, String code, String message)
            throws IOException {
        List<SSEEvent> events = List.of(
                new SSEEvent("message_start", run.runId(), run.sessionId(), null, run.traceId(),
                        null, null, null, null, null, null, null, null, null),
                SSEEvent.error(run.runId(), code, message),
                SSEEvent.of("message_end", run.runId()));
        for (SSEEvent event : events) {
            writer.write("event: " + event.type() + "\n");
            writer.write("data: " + objectMapper.writeValueAsString(event) + "\n\n");
            writer.flush();
            if (writer.checkError()) {
                throw new IOException("sse client disconnected");
            }
        }
    }

    private static String summarize(ChatSessionDetail detail) {
        List<String> parts = new ArrayList<>();
        for (var message : detail.messages()) {
            String content = message.content() == null ? "" : message.content().trim();
            if (!content.isEmpty()) {
                parts.add(content);
            }
            if (parts.size() >= 3) {
                break;
            }
        }
        String summary = String.join("；", parts);
        if (summary.isEmpty()) {
            summary = detail.session().summary();
        }
        return summary;
    }

    private static String shortTitle(String summary) {
        String cleaned = summary == null ? "" : summary.trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        cleaned = cleaned.replaceAll("[，。？！,?!]", "");
        return cleaned.codePointCount(0, cleaned.length()) <= 12 ? cleaned
                : cleaned.substring(0, cleaned.offsetByCodePoints(0, 12));
    }

    private static PageResponse<ChatSession> paginate(List<ChatSession> all, PageRequest page) {
        int total = all.size();
        int start = page.offset();
        if (start >= total) {
            return new PageResponse<>(List.of(), page.page(), page.pageSize(), total);
        }
        int end = Math.min(start + page.pageSize(), total);
        return new PageResponse<>(all.subList(start, end), page.page(), page.pageSize(), total);
    }

    private static Account requireUser(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"user".equals(account.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是用户端账号");
        }
        return account;
    }

    private static ApiException badRequestBody() {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
    }
}
