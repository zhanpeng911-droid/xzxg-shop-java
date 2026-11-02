package com.xzxg.shop.common.web;

import com.xzxg.shop.common.api.ApiErrorWriter;
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
 * Rejects oversized request bodies early, mirroring the Go 12 MiB
 * {@code http.MaxBytesReader} limit. Only requests declaring a
 * {@code Content-Length} are checked here; chunked bodies are bounded by the
 * container's swallow limit and multipart limits.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class BodySizeLimitFilter extends OncePerRequestFilter {

    private static final HttpStatus PAYLOAD_TOO_LARGE = HttpStatus.valueOf(413);

    private final long maxRequestBodyBytes;
    private final ApiErrorWriter errorWriter;

    public BodySizeLimitFilter(ShopProperties properties, ApiErrorWriter errorWriter) {
        this.maxRequestBodyBytes = properties.getWeb().getMaxRequestBodyBytes();
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean hasBody = !HttpMethod.GET.matches(request.getMethod())
                && !HttpMethod.HEAD.matches(request.getMethod());
        if (maxRequestBodyBytes > 0
                && hasBody
                && request.getContentLengthLong() > maxRequestBodyBytes) {
            errorWriter.write(request, response, PAYLOAD_TOO_LARGE,
                    "payload_too_large", "请求体超过大小限制");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
