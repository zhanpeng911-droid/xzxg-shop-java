package com.xzxg.shop.account;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.common.Ids;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.domain.Account;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class AccountRepository {

    private static final String ACCOUNT_COLUMNS =
            "account_id, username, display_name, avatar_url, phone, email, role, merchant_id, status, created_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public AccountRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public record AccountWithHash(Account account, String passwordHash) {
    }

    public Optional<AccountWithHash> findActiveByUsername(String username) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT account_id, username, password_hash, display_name, avatar_url, phone, email,
                           role, merchant_id, status, created_at
                    FROM accounts
                    WHERE username = ? AND status = 'active'
                    """, (rs, rowNum) -> new AccountWithHash(mapAccount(rs), rs.getString("password_hash")),
                    username));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<Account> findById(String accountId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + ACCOUNT_COLUMNS + " FROM accounts WHERE account_id = ?",
                    ACCOUNT_MAPPER, accountId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<Account> findByToken(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT a.account_id, a.username, a.display_name, a.avatar_url, a.phone, a.email,
                           a.role, a.merchant_id, a.status, a.created_at
                    FROM auth_tokens t
                    JOIN accounts a ON a.account_id = t.account_id
                    WHERE t.token IN (?, ?) AND t.expires_at > ? AND a.status IN ('active', 'risk')
                    """, ACCOUNT_MAPPER, sha256Hex(trimmed), trimmed, Timestamp.valueOf(LocalDateTime.now())));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    public Account create(String username, String passwordHash, String displayName, String role,
                          String merchantId) {
        LocalDateTime now = LocalDateTime.now();
        Account account = new Account(Ids.next("acct"), username, displayName, "", "", "", role,
                merchantId == null ? "" : merchantId, "active", GoTime.format(now));
        jdbc.update("""
                INSERT INTO accounts (account_id, username, password_hash, display_name, avatar_url, phone,
                                      email, role, merchant_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, '', '', '', ?, ?, 'active', ?, ?)
                """, account.accountId(), account.username(), passwordHash, account.displayName(),
                account.role(), account.merchantId(), Timestamp.valueOf(now), Timestamp.valueOf(now));
        return account;
    }

    public boolean updatePasswordHash(String accountId, String passwordHash) {
        if (accountId == null || accountId.isBlank() || passwordHash == null || passwordHash.isBlank()) {
            return false;
        }
        return jdbc.update("""
                UPDATE accounts SET password_hash = ?, updated_at = ?
                WHERE account_id = ? AND status IN ('active', 'risk')
                """, passwordHash, Timestamp.valueOf(LocalDateTime.now()), accountId) > 0;
    }

    public Optional<Account> updateProfile(String accountId, String displayName, String avatarUrl) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        sets.add("updated_at = ?");
        args.add(Timestamp.valueOf(LocalDateTime.now()));
        if (displayName != null && !displayName.isBlank()) {
            sets.add("display_name = ?");
            args.add(truncate(displayName, 24));
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            sets.add("avatar_url = ?");
            args.add(truncate(avatarUrl, 512));
        }
        args.add(accountId);
        int affected = jdbc.update("UPDATE accounts SET " + String.join(", ", sets)
                + " WHERE account_id = ? AND status = 'active'", args.toArray());
        return affected > 0 ? findById(accountId) : Optional.empty();
    }

    public Optional<Account> updateContact(String accountId, String phone, String email) {
        int affected = jdbc.update("""
                UPDATE accounts SET phone = ?, email = ?, updated_at = ?
                WHERE account_id = ? AND status = 'active'
                """, truncate(phone, 32), truncate(email, 128),
                Timestamp.valueOf(LocalDateTime.now()), accountId);
        return affected > 0 ? findById(accountId) : Optional.empty();
    }

    public boolean deleteAccount(String accountId) {
        Boolean committed = transactions.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int affected = jdbc.update("""
                    UPDATE accounts SET status = 'deleted', deleted_at = ?, updated_at = ?
                    WHERE account_id = ? AND status = 'active'
                    """, Timestamp.valueOf(now), Timestamp.valueOf(now), accountId);
            if (affected == 0) {
                status.setRollbackOnly();
                return false;
            }
            jdbc.update("DELETE FROM auth_tokens WHERE account_id = ?", accountId);
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    public PageResponse<Account> listPage(PageRequest page) {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);
        List<Account> items = jdbc.query("""
                SELECT account_id, username, display_name, avatar_url, phone, email, role, merchant_id,
                       status, created_at
                FROM accounts
                ORDER BY created_at, account_id
                LIMIT ? OFFSET ?
                """, ACCOUNT_MAPPER, page.pageSize(), page.offset());
        return new PageResponse<>(items, page.page(), page.pageSize(), total == null ? 0 : total);
    }

    public Optional<Account> updateStatus(String accountId, String status) {
        int affected = jdbc.update(
                "UPDATE accounts SET status = ?, updated_at = ? WHERE account_id = ?",
                status, Timestamp.valueOf(LocalDateTime.now()), accountId);
        return affected > 0 ? findById(accountId) : Optional.empty();
    }

    private static final RowMapper<Account> ACCOUNT_MAPPER =
            (rs, rowNum) -> mapAccount(rs);

    private static Account mapAccount(ResultSet rs) throws SQLException {
        LocalDateTime createdAt = timestamp(rs, "created_at");
        return new Account(
                rs.getString("account_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                nullToEmpty(rs.getString("avatar_url")),
                nullToEmpty(rs.getString("phone")),
                nullToEmpty(rs.getString("email")),
                rs.getString("role"),
                nullToEmpty(rs.getString("merchant_id")),
                nullToEmpty(rs.getString("status")),
                GoTime.format(createdAt));
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? GoTime.zero() : value.toLocalDateTime();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxRunes) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= maxRunes) {
            return trimmed;
        }
        int end = trimmed.offsetByCodePoints(0, maxRunes);
        return trimmed.substring(0, end);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (value == null ? "" : value.trim()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
