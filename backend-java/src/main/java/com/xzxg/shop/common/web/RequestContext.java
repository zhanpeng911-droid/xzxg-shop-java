package com.xzxg.shop.common.web;

import com.xzxg.shop.domain.Account;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Request-scoped attribute names shared by filters and error handlers.
 */
public final class RequestContext {

    public static final String REQUEST_ID_ATTRIBUTE = RequestContext.class.getName() + ".requestId";
    public static final String CLIENT_IP_ATTRIBUTE = RequestContext.class.getName() + ".clientIp";
    public static final String ACCOUNT_ATTRIBUTE = RequestContext.class.getName() + ".account";
    /** Set by the auth layer as {@code role:accountId}; absent for anonymous calls. */
    public static final String ACCOUNT_KEY_ATTRIBUTE = RequestContext.class.getName() + ".accountKey";

    private RequestContext() {
    }

    public static void setAccount(HttpServletRequest request, Account account) {
        request.setAttribute(ACCOUNT_ATTRIBUTE, account);
        request.setAttribute(ACCOUNT_KEY_ATTRIBUTE, account.role() + ":" + account.accountId());
    }

    public static Account account(HttpServletRequest request) {
        Object value = request.getAttribute(ACCOUNT_ATTRIBUTE);
        return value instanceof Account account ? account : null;
    }

    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value == null ? "" : value.toString();
    }

    public static String clientIp(HttpServletRequest request) {
        Object value = request.getAttribute(CLIENT_IP_ATTRIBUTE);
        if (value != null) {
            return value.toString();
        }
        return request.getRemoteAddr();
    }

    public static String rateLimitKey(HttpServletRequest request) {
        Object accountKey = request.getAttribute(ACCOUNT_KEY_ATTRIBUTE);
        if (accountKey != null) {
            return accountKey.toString();
        }
        return clientIp(request);
    }
}
