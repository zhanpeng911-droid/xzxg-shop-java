package com.xzxg.shop.common.web;

import com.xzxg.shop.common.api.ApiErrorWriter;
import com.xzxg.shop.common.ratelimit.FixedWindowRateLimiter;
import com.xzxg.shop.config.ShopProperties;
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
 * Mirrors the Go limiter: fixed window, 120 requests per minute, keyed by
 * {@code role:account_id} when authenticated and by client IP otherwise.
 * OPTIONS, the health endpoint, and {@code /api/v1/eval/} are not limited.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String HEALTH_PATH = "/api/v1/health";
    private static final String EVAL_PREFIX = "/api/v1/eval/";

    private final boolean enabled;
    private final FixedWindowRateLimiter limiter;
    private final ApiErrorWriter errorWriter;

    public RateLimitFilter(ShopProperties properties, ApiErrorWriter errorWriter) {
        ShopProperties.RateLimit config = properties.getWeb().getRateLimit();
        this.enabled = config.isEnabled();
        this.errorWriter = errorWriter;
        this.limiter = enabled
                ? new FixedWindowRateLimiter(config.getLimit(), config.getWindowSeconds(),
                        config.getMaxEntries())
                : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled || isExempt(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!limiter.tryAcquire(RequestContext.rateLimitKey(request))) {
            errorWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limited", "请求过于频繁，请稍后再试");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isExempt(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return HEALTH_PATH.equals(path) || path.startsWith(EVAL_PREFIX);
    }
}
