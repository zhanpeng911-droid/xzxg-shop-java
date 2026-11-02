package com.xzxg.shop.common.web;

import com.xzxg.shop.config.ShopProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns {@code X-Request-ID} to every request. Generated ids match the Go
 * format (12 random bytes as 24 lowercase hex characters). Client-supplied ids
 * are reused unless they contain control characters or are unreasonably long;
 * that cap is a deliberate hardening over the Go implementation, which echoes
 * any non-empty value.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String MDC_KEY = "request_id";
    private static final int MAX_CLIENT_REQUEST_ID_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String headerName;

    public RequestIdFilter(ShopProperties properties) {
        this.headerName = properties.getWeb().getRequestIdHeader();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(headerName);
        if (!isAcceptable(requestId)) {
            requestId = newRequestId();
        }

        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(headerName, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static boolean isAcceptable(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_CLIENT_REQUEST_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static String newRequestId() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
