package com.xzxg.shop.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stores the resolved client address for rate limiting and audit logging.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ClientIpFilter extends OncePerRequestFilter {

    private final ClientIpResolver resolver;

    public ClientIpFilter(ClientIpResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        request.setAttribute(RequestContext.CLIENT_IP_ATTRIBUTE, resolver.resolve(request));
        filterChain.doFilter(request, response);
    }
}
