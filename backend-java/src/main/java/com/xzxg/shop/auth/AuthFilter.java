package com.xzxg.shop.auth;

import com.xzxg.shop.account.AccountRepository;
import com.xzxg.shop.common.api.ApiErrorWriter;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Opaque-token authentication and role checks, mirroring the Go withAuth wrapper.
 * Runs before the rate limiter so authenticated requests are keyed by
 * {@code role:account_id}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 35)
public class AuthFilter extends OncePerRequestFilter {

    private final AccountRepository accounts;
    private final ApiErrorWriter errorWriter;

    public AuthFilter(AccountRepository accounts, ApiErrorWriter errorWriter) {
        this.accounts = accounts;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = bearerToken(request);
        Account account = token.isEmpty()
                ? null
                : accounts.findByToken(token).orElse(null);
        if (account != null) {
            RequestContext.setAccount(request, account);
        }

        RoutePolicy policy = RoutePolicy.of(request.getMethod(), request.getRequestURI());
        if (!policy.required()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (account == null) {
            errorWriter.write(request, response, HttpStatus.UNAUTHORIZED,
                    "unauthorized", "请先登录");
            return;
        }
        if (!policy.roles().contains(account.role())) {
            errorWriter.write(request, response, HttpStatus.FORBIDDEN,
                    "forbidden", "当前账号无权访问");
            return;
        }
        filterChain.doFilter(request, response);
    }

    public static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return "";
        }
        return header.substring(7).trim();
    }
}
