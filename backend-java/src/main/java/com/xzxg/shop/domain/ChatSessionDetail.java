package com.xzxg.shop.domain;

import java.util.List;

public record ChatSessionDetail(ChatSession session, List<UserMessageWithRuns> messages) {
}
