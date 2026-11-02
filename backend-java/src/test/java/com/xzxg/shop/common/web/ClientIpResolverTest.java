package com.xzxg.shop.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.xzxg.shop.config.ShopProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    @Test
    void ignoresForwardedHeadersWhenNoProxyIsTrusted() {
        ShopProperties properties = new ShopProperties();
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
    }

    @Test
    void usesFirstForwardedEntryWhenPeerIsTrusted() {
        ShopProperties properties = new ShopProperties();
        properties.getWeb().setTrustedProxies(List.of("10.0.0.0/8"));
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.1.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.2.2.2");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void doesNotWalkPastTrustedEntriesLikeGo() {
        ShopProperties properties = new ShopProperties();
        properties.getWeb().setTrustedProxies(List.of("10.0.0.0/8"));
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.1.1");
        request.addHeader("X-Forwarded-For", "10.9.9.9, 203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("10.9.9.9");
    }

    @Test
    void usesRemoteAddressWhenPeerIsNotTrusted() {
        ShopProperties properties = new ShopProperties();
        properties.getWeb().setTrustedProxies(List.of("10.0.0.0/8"));
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void ignoresXRealIpBecauseGoDoesNotReadIt() {
        ShopProperties properties = new ShopProperties();
        properties.getWeb().setTrustedProxies(List.of("127.0.0.1/32"));
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.8");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void trustAllProxiesUsesForwardedHeaderFromAnyPeer() {
        ShopProperties properties = new ShopProperties();
        properties.getWeb().setTrustAllProxies(true);
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void invalidForwardedEntryFallsBackToRemoteAddress() {
        ShopProperties properties = new ShopProperties();
        properties.getWeb().setTrustAllProxies(true);
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");
        request.addHeader("X-Forwarded-For", "not-an-ip, 203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }
}
