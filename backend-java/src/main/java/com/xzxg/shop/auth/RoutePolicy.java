package com.xzxg.shop.auth;

import java.util.Set;

/**
 * Port of the Go {@code routePolicy}. Anything not listed is public and left to
 * the router (which may still return 404).
 */
public record RoutePolicy(boolean required, Set<String> roles) {

    private static final Set<String> ALL_ROLES = Set.of("user", "merchant", "admin");

    public static RoutePolicy of(String method, String path) {
        if ("/api/v1/health".equals(path)
                || "/api/v1/auth/login".equals(path)
                || "/api/v1/auth/register".equals(path)) {
            return publicAccess();
        }
        if ("GET".equals(method)
                && ("/api/v1/categories/tree".equals(path)
                || "/api/v1/merchants".equals(path)
                || "/api/v1/products".equals(path)
                || path.startsWith("/api/v1/products/"))) {
            return publicAccess();
        }
        if ("GET".equals(method) && path.startsWith("/api/v1/uploads/avatar/")) {
            return publicAccess();
        }
        if ("/api/v1/auth/me".equals(path)
                || "/api/v1/auth/logout".equals(path)
                || path.startsWith("/api/v1/account")
                || "/api/v1/uploads/avatar".equals(path)) {
            return new RoutePolicy(true, ALL_ROLES);
        }
        if (path.startsWith("/api/v1/cart")
                || path.startsWith("/api/v1/agent")
                || path.startsWith("/api/v1/orders")
                || path.startsWith("/api/v1/coupons")
                || path.startsWith("/api/v1/speech")) {
            return new RoutePolicy(true, Set.of("user"));
        }
        if (path.startsWith("/api/v1/files") || path.startsWith("/api/v1/search/image")) {
            return new RoutePolicy(true, ALL_ROLES);
        }
        if (path.startsWith("/api/v1/merchant")) {
            return new RoutePolicy(true, Set.of("merchant"));
        }
        if (path.startsWith("/api/v1/admin")) {
            return new RoutePolicy(true, Set.of("admin"));
        }
        return publicAccess();
    }

    private static RoutePolicy publicAccess() {
        return new RoutePolicy(false, Set.of());
    }
}
