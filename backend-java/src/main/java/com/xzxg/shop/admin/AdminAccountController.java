package com.xzxg.shop.admin;

import com.xzxg.shop.account.AccountRepository;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminAccountController {

    private final AccountRepository accounts;

    public AdminAccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public record StatusRequest(String status) {
    }

    @GetMapping("/api/v1/admin/accounts")
    public PageResponse<Account> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        requireAdmin(request);
        return accounts.listPage(PageRequest.of(page, pageSize));
    }

    @PatchMapping("/api/v1/admin/accounts/{accountId}")
    public Account update(@PathVariable String accountId,
                          @RequestBody(required = false) StatusRequest body,
                          HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
        }
        if (!allowedStatus(body.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_status", "账号状态不合法");
        }
        return accounts.updateStatus(accountId, body.status())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "account_not_found", "账号不存在"));
    }

    private static void requireAdmin(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"admin".equals(account.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是管理员");
        }
    }

    private static boolean allowedStatus(String status) {
        return "active".equals(status) || "inactive".equals(status) || "risk".equals(status);
    }
}
