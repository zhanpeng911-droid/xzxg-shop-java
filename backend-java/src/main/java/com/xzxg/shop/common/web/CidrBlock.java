package com.xzxg.shop.common.web;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Minimal CIDR matcher used for trusted-proxy checks. Only literal addresses are
 * accepted; host names are rejected so that startup never performs a DNS lookup.
 */
public final class CidrBlock {

    private final byte[] network;
    private final int prefixLength;

    private CidrBlock(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    public static CidrBlock parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CIDR block must not be blank");
        }
        String text = value.trim();
        String addressPart = text;
        int prefix = -1;

        int slash = text.indexOf('/');
        if (slash >= 0) {
            addressPart = text.substring(0, slash);
            String prefixPart = text.substring(slash + 1);
            try {
                prefix = Integer.parseInt(prefixPart);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid CIDR prefix: " + value, ex);
            }
        }

        InetAddress address = parseLiteral(addressPart);
        byte[] bytes = address.getAddress();
        if (prefix < 0) {
            prefix = bytes.length * 8;
        }
        if (prefix > bytes.length * 8) {
            throw new IllegalArgumentException("CIDR prefix out of range: " + value);
        }
        return new CidrBlock(bytes, prefix);
    }

    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    static InetAddress parseLiteral(String value) {
        String text = value.trim();
        if (text.isEmpty() || !looksLikeIpLiteral(text)) {
            throw new IllegalArgumentException("Not an IP literal: " + value);
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Not an IP literal: " + value, ex);
        }
    }

    private static boolean looksLikeIpLiteral(String text) {
        if (text.indexOf(':') >= 0) {
            return text.matches("[0-9A-Fa-f:.%]+");
        }
        return text.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}
