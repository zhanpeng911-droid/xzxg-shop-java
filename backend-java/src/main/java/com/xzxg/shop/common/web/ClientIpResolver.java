package com.xzxg.shop.common.web;

import com.xzxg.shop.config.ShopProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Resolves the client address the way the Go middleware does: only when the
 * direct peer is trusted (or {@code trust-all-proxies} is enabled) is the first
 * entry of {@code X-Forwarded-For} used. There is no {@code X-Real-IP} fallback.
 */
@Component
public class ClientIpResolver {

    private final boolean trustAllProxies;
    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver(ShopProperties properties) {
        this.trustAllProxies = properties.getWeb().isTrustAllProxies();
        this.trustedProxies = properties.getWeb().getTrustedProxies().stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(CidrBlock::parse)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!trustAllProxies) {
            if (trustedProxies.isEmpty()) {
                return remote;
            }
            InetAddress remoteAddress = tryParse(remote);
            if (remoteAddress == null || !isTrusted(remoteAddress)) {
                return remote;
            }
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        String[] parts = forwarded.split(",");
        String candidate = parts[0].trim();
        InetAddress address = tryParse(candidate);
        return address == null ? remote : address.getHostAddress();
    }

    private boolean isTrusted(InetAddress address) {
        for (CidrBlock block : trustedProxies) {
            if (block.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static InetAddress tryParse(String value) {
        try {
            return CidrBlock.parseLiteral(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
