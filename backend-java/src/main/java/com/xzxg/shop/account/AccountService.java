package com.xzxg.shop.account;

import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.domain.Account;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account updateProfile(Account account, String displayName, String nickname, String avatarUrl) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            name = nickname == null ? "" : nickname.trim();
        }
        String avatar = avatarUrl == null ? "" : avatarUrl.trim();
        if (name.isEmpty() && avatar.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_profile", "昵称或头像不能为空");
        }
        if (!name.isEmpty() && name.codePointCount(0, name.length()) > 24) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_display_name", "昵称最多 24 个字符");
        }
        return accounts.updateProfile(account.accountId(), name, avatar)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "account_not_found", "账号不存在"));
    }

    public Account updateContact(Account account, String phone, String email) {
        String trimmedPhone = phone == null ? "" : phone.trim();
        String trimmedEmail = email == null ? "" : email.trim();
        if (trimmedPhone.codePointCount(0, trimmedPhone.length()) > 32
                || trimmedEmail.codePointCount(0, trimmedEmail.length()) > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_contact", "手机号或邮箱过长");
        }
        if (!trimmedEmail.isEmpty()
                && (!trimmedEmail.contains("@") || trimmedEmail.contains(" "))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_email", "邮箱格式不正确");
        }
        return accounts.updateContact(account.accountId(), trimmedPhone, trimmedEmail)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "account_not_found", "账号不存在"));
    }

    public void delete(Account account) {
        if (!accounts.deleteAccount(account.accountId())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "delete_account_failed", "删除账号失败");
        }
    }

    public Optional<Account> find(String accountId) {
        return accounts.findById(accountId);
    }
}
