package com.xzxg.shop.auth;

import com.xzxg.shop.account.AccountRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * bcrypt for new passwords, with the historical SHA-256 hex check and upgrade
 * behaviour preserved from the Go backend.
 */
@Service
public class PasswordService {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(10);

    public String hash(String rawPassword) {
        return BCRYPT.encode(rawPassword);
    }

    public boolean matches(String storedHash, String rawPassword) {
        String stored = storedHash == null ? "" : storedHash.trim();
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return BCRYPT.matches(rawPassword, stored);
        }
        return AccountRepository.sha256Hex(rawPassword).equals(stored);
    }

    public boolean needsUpgrade(String storedHash) {
        String stored = storedHash == null ? "" : storedHash.trim();
        return !stored.startsWith("$2");
    }
}
