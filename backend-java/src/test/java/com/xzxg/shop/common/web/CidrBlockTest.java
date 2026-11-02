package com.xzxg.shop.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class CidrBlockTest {

    @Test
    void matchesIpv4Prefix() throws Exception {
        CidrBlock block = CidrBlock.parse("10.0.0.0/8");

        assertThat(block.contains(InetAddress.getByName("10.1.2.3"))).isTrue();
        assertThat(block.contains(InetAddress.getByName("10.255.255.255"))).isTrue();
        assertThat(block.contains(InetAddress.getByName("11.0.0.1"))).isFalse();
    }

    @Test
    void matchesSingleAddressWithoutPrefix() throws Exception {
        CidrBlock block = CidrBlock.parse("192.168.1.5");

        assertThat(block.contains(InetAddress.getByName("192.168.1.5"))).isTrue();
        assertThat(block.contains(InetAddress.getByName("192.168.1.6"))).isFalse();
    }

    @Test
    void matchesIpv6Loopback() throws Exception {
        CidrBlock block = CidrBlock.parse("::1/128");

        assertThat(block.contains(InetAddress.getByName("::1"))).isTrue();
        assertThat(block.contains(InetAddress.getByName("::2"))).isFalse();
    }

    @Test
    void rejectsHostNamesAndBadPrefixes() {
        assertThatThrownBy(() -> CidrBlock.parse("example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrBlock.parse("10.0.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrBlock.parse("10.0.0.0/abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
