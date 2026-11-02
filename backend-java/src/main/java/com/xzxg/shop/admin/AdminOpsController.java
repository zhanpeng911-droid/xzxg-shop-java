package com.xzxg.shop.admin;

import com.xzxg.shop.agent.AgentRepository;
import com.xzxg.shop.agent.PromptRepository;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.AgentPrompt;
import com.xzxg.shop.domain.AgentPromptPublishRecord;
import com.xzxg.shop.domain.AgentRun;
import com.xzxg.shop.domain.AgentTraceEvent;
import com.xzxg.shop.domain.VectorIndexStatus;
import com.xzxg.shop.retrieval.VectorStatusService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminOpsController {

    private final PromptRepository prompts;
    private final AgentRepository runs;
    private final VectorStatusService vectorStatus;

    public AdminOpsController(PromptRepository prompts, AgentRepository runs,
                              VectorStatusService vectorStatus) {
        this.prompts = prompts;
        this.runs = runs;
        this.vectorStatus = vectorStatus;
    }

    public record PromptDraftRequest(String title, String content, String description) {
    }

    @GetMapping("/api/v1/admin/prompts")
    public Map<String, Object> listPrompts(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        requireAdmin(request);
        PageRequest pageRequest = PageRequest.of(page, pageSize);
        PageResponse<AgentPrompt> result = prompts.listLatest(pageRequest);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", result.items());
        response.put("page", result.page());
        response.put("page_size", result.pageSize());
        response.put("total", result.total());
        response.put("publish_records", prompts.publishRecords("", 20));
        return response;
    }

    @PatchMapping("/api/v1/admin/prompts/{promptKey}")
    public AgentPrompt draft(@PathVariable String promptKey,
                             @RequestBody(required = false) PromptDraftRequest body,
                             HttpServletRequest request) {
        Account admin = requireAdmin(request);
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
        }
        try {
            return prompts.saveDraft(promptKey, body.title(), body.content(), body.description(),
                    admin.accountId());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "save_prompt_failed", ex.getMessage());
        }
    }

    @PostMapping("/api/v1/admin/prompts/{promptKey}/publish")
    public Map<String, Object> publish(@PathVariable String promptKey, HttpServletRequest request) {
        Account admin = requireAdmin(request);
        try {
            PromptRepository.PublishResult result = prompts.publish(promptKey,
                    admin.accountId(), "database");
            return Map.of("prompt", result.prompt(), "record", result.record());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "publish_prompt_failed", ex.getMessage());
        }
    }

    @GetMapping("/api/v1/admin/agent/runs")
    public PageResponse<AgentRun> listRuns(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        requireAdmin(request);
        PageRequest pageRequest = PageRequest.of(page, pageSize);
        return new PageResponse<>(runs.listRunsPage(pageRequest), pageRequest.page(),
                pageRequest.pageSize(), runs.countRuns());
    }

    @GetMapping("/api/v1/admin/agent/runs/{runId}/trace")
    public Map<String, List<AgentTraceEvent>> runTrace(@PathVariable String runId,
                                                       HttpServletRequest request) {
        requireAdmin(request);
        return Map.of("items", runs.listTraceByRun(runId));
    }

    @GetMapping("/api/v1/admin/vector/status")
    public VectorIndexStatus vectorStatus(HttpServletRequest request) {
        requireAdmin(request);
        return vectorStatus.status();
    }

    private static Account requireAdmin(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"admin".equals(account.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是管理员");
        }
        return account;
    }
}
