package com.xzxg.shop.common.web;

import com.xzxg.shop.config.ShopProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mirrors the Go {@code withCORS} wrapper: wildcard origin by default, fixed
 * allowed headers/methods, and an empty 204 for every OPTIONS request.
 * Runs outside the request-id filter so preflight responses carry no request id,
 * matching the Go middleware order.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter extends OncePerRequestFilter {

    private static final String ALLOWED_HEADERS = "Content-Type, Accept, Authorization";
    private static final String ALLOWED_METHODS = "GET, POST, PATCH, DELETE, OPTIONS";

    private final List<String> allowedOrigins;

    public CorsFilter(ShopProperties properties) {
        this.allowedOrigins = properties.getWeb().getCors().getAllowedOrigins().stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String allowedOrigin = resolveAllowedOrigin(request.getHeader("Origin"));
        if (!allowedOrigin.isEmpty()) {
            response.setHeader("Access-Control-Allow-Origin", allowedOrigin);
            response.setHeader("Vary", "Origin");
        }
        response.setHeader("Access-Control-Allow-Headers", ALLOWED_HEADERS);
        response.setHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);

        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String resolveAllowedOrigin(String origin) {
        if (allowedOrigins.isEmpty() || allowedOrigins.contains("*")) {
            return "*";
        }
        if (origin == null || origin.isBlank()) {
            return "";
        }
        String trimmed = origin.trim();
        return allowedOrigins.contains(trimmed) ? trimmed : "";
    }
}
