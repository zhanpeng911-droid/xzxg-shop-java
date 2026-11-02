package com.xzxg.shop.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Opaque Bearer tokens, matching the Go implementation: {@code tok_} + base64url
 * of 32 random bytes, stored as a SHA-256 hex digest with a 24 hour lifetime.
 * Lookups also accept the legacy plaintext token value.
 */
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public TokenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String create(String accountId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = "tok_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO auth_tokens (token, account_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, hash(token), accountId, Timestamp.valueOf(now),
                Timestamp.valueOf(now.plusHours(24)));
        return token;
    }

    public boolean delete(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        jdbc.update("DELETE FROM auth_tokens WHERE token IN (?, ?)", hash(trimmed), trimmed);
        return true;
    }

    public boolean deleteByAccount(String accountId) {
        jdbc.update("DELETE FROM auth_tokens WHERE account_id = ?", accountId);
        return true;
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (token == null ? "" : token.trim()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
