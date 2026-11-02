package com.xzxg.shop.auth;

import com.xzxg.shop.account.AccountRepository;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.domain.Account;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AccountRepository accounts;
    private final PasswordService passwords;
    private final TokenService tokens;

    public AuthService(AccountRepository accounts, PasswordService passwords, TokenService tokens) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    public record TokenResponse(String token, Account account) {
    }

    public TokenResponse login(String rawUsername, String password) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        if (username.isEmpty() || password == null || password.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_credential", "账号和密码不能为空");
        }
        AccountRepository.AccountWithHash found = accounts.findActiveByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "invalid_credential", "账号或密码错误"));
        if (!passwords.matches(found.passwordHash(), password)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credential", "账号或密码错误");
        }
        if (passwords.needsUpgrade(found.passwordHash())) {
            accounts.updatePasswordHash(found.account().accountId(), passwords.hash(password));
        }
        return new TokenResponse(tokens.create(found.account().accountId()), found.account());
    }

    public TokenResponse register(String rawUsername, String password, String rawDisplayName) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        String displayName = rawDisplayName == null ? "" : rawDisplayName.trim();
        if (displayName.isEmpty()) {
            displayName = username;
        }
        validateRegistration(username, password, displayName);
        if (accounts.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "username_exists", "账号已存在");
        }
        Account account = accounts.create(username, passwords.hash(password), displayName, "user", "");
        String token = tokens.create(account.accountId());
        return new TokenResponse(token, account);
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            tokens.delete(token);
        }
    }

    private static void validateRegistration(String username, String password, String displayName) {
        if (username.isEmpty() || password == null || password.isEmpty()) {
            throw invalidRegistration("账号和密码不能为空");
        }
        int usernameLength = username.codePointCount(0, username.length());
        if (usernameLength < 3 || usernameLength > 32) {
            throw invalidRegistration("账号长度需要在 3 到 32 个字符之间");
        }
        for (int i = 0; i < username.length(); ) {
            int codePoint = username.codePointAt(i);
            boolean allowed = (codePoint >= 'a' && codePoint <= 'z')
                    || (codePoint >= 'A' && codePoint <= 'Z')
                    || (codePoint >= '0' && codePoint <= '9')
                    || codePoint == '_' || codePoint == '-';
            if (!allowed) {
                throw invalidRegistration("账号只能包含字母、数字、下划线和短横线");
            }
            i += Character.charCount(codePoint);
        }
        int passwordLength = password.codePointCount(0, password.length());
        if (passwordLength < 8 || passwordLength > 64) {
            throw invalidRegistration("密码长度需要在 8 到 64 个字符之间");
        }
        if (displayName.codePointCount(0, displayName.length()) > 32) {
            throw invalidRegistration("昵称不能超过 32 个字符");
        }
    }

    private static ApiException invalidRegistration(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_register_input", message);
    }
}
