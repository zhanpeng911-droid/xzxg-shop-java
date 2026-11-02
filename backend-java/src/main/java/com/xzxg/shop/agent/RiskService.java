package com.xzxg.shop.agent;

import com.xzxg.shop.domain.Account;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Port of the default Go risk rules (Nacos overrides are not wired yet). */
@Service
public class RiskService {

    public record Result(boolean blocked, String code, String message, String matched) {
    }

    private static final List<String> DEFAULT_WORDS = List.of("违法", "违禁", "假货", "绕过风控");
    private static final String DEFAULT_MESSAGE = "这个请求涉及平台风控限制，无法继续处理。";

    public Result checkText(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String word : DEFAULT_WORDS) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                return new Result(true, "unsafe_request", DEFAULT_MESSAGE, word);
            }
        }
        return new Result(false, "", "", "");
    }

    public Result checkAccount(Account account) {
        if (account != null && "risk".equals(account.status())) {
            return new Result(true, "risk_account",
                    "当前账号命中平台风控限制，暂时无法继续使用导购 Agent。",
                    account.status());
        }
        return new Result(false, "", "", "");
    }
}
