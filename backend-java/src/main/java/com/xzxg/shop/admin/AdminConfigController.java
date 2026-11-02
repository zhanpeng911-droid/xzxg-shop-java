package com.xzxg.shop.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.config.ConfigService;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.AppConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminConfigController {

    private final ConfigService configs;

    public AdminConfigController(ConfigService configs) {
        this.configs = configs;
    }

    public record ConfigUpdateRequest(
            String value,
            @JsonProperty("value_type") String valueType,
            String description,
            @JsonProperty("is_secret") Boolean isSecret) {
    }

    @GetMapping("/api/v1/admin/configs")
    public PageResponse<AppConfig> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        requireAdmin(request);
        PageRequest pageRequest = PageRequest.of(page, pageSize);
        List<AppConfig> all = configs.list().stream()
                .filter(item -> !item.configKey().startsWith("agent.prompt."))
                .map(configs::masked)
                .toList();
        int start = pageRequest.offset();
        List<AppConfig> items = start >= all.size() ? List.of()
                : all.subList(start, Math.min(start + pageRequest.pageSize(), all.size()));
        return new PageResponse<>(items, pageRequest.page(), pageRequest.pageSize(), all.size());
    }

    @PatchMapping("/api/v1/admin/configs/{configKey}")
    public AppConfig update(@PathVariable String configKey,
                            @RequestBody(required = false) ConfigUpdateRequest body,
                            HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
        }
        return configs.update(configKey, body.value(), body.valueType(), body.description(),
                body.isSecret());
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
