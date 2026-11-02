package com.xzxg.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.xzxg.shop.account.AccountRepository;
import com.xzxg.shop.agent.AgentRepository;
import com.xzxg.shop.cart.CartRepository;
import com.xzxg.shop.domain.Cart;
import com.xzxg.shop.domain.ChatSession;
import com.xzxg.shop.domain.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Concurrent message/run idempotency and cart quantity under a real MySQL schema. */
@SpringBootTest
@ActiveProfiles("mysql-it")
class IdempotencyIT {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AgentRepository agents;

    @Autowired
    private CartRepository carts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentDuplicateMessageAndRunCreateOnlyOneRow() throws Exception {
        String accountId = accounts.create("idem_" + System.nanoTime(),
                AccountRepository.sha256Hex("password123"), "并发测试", "user", "").accountId();
        ChatSession session = agents.createSession(accountId, "并发测试");

        List<AgentRepository.CreateMessageResult> messages = runConcurrently(8,
                () -> agents.createUserMessage(new UserMessage(null, session.sessionId(),
                        accountId, "same-client-id", "并发消息", List.of(), "")));
        assertThat(messages.stream().filter(AgentRepository.CreateMessageResult::created).count())
                .isEqualTo(1);
        assertThat(messages.stream().map(result -> result.message().messageId()).distinct().count())
                .isEqualTo(1);
        String messageId = messages.get(0).message().messageId();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_messages
                WHERE account_id = ? AND session_id = ? AND client_message_id = 'same-client-id'
                """, Integer.class, accountId, session.sessionId())).isEqualTo(1);

        List<AgentRepository.CreateRunResult> runs = runConcurrently(8,
                () -> agents.createRun(accountId, session.sessionId(), messageId));
        assertThat(runs.stream().filter(AgentRepository.CreateRunResult::created).count())
                .isEqualTo(1);
        assertThat(runs.stream().map(result -> result.run().runId()).distinct().count())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_runs WHERE account_id = ? AND message_id = ?
                """, Integer.class, accountId, messageId)).isEqualTo(1);
    }

    @Test
    void concurrentAddSameProductKeepsAllQuantities() throws Exception {
        String accountId = accounts.create("cart_" + System.nanoTime(),
                AccountRepository.sha256Hex("password123"), "并发购物车", "user", "").accountId();
        List<Cart> results = runConcurrently(8,
                () -> carts.addItem(accountId, "p_001", "sku_001", 1).orElseThrow());
        assertThat(results).hasSize(8);
        Integer quantity = jdbc.queryForObject("""
                SELECT quantity FROM cart_items
                WHERE account_id = ? AND product_id = 'p_001' AND sku_id = 'sku_001'
                """, Integer.class, accountId);
        assertThat(quantity).isEqualTo(8);
    }

    private static <T> List<T> runConcurrently(int threads, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Task<T> {
        T call() throws Exception;
    }
}
