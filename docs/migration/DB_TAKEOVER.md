# 数据库初始化与旧库接管

## 1. 新库初始化

`backend-java/src/main/resources/db/migration/V1__baseline_schema.sql` 是 Go 基线
`cabd0a1b` 的最终运行 schema：22 张基础表 + `stored_files`，共 23 张，无任何 `INSERT`。

```bash
cd backend-java
SHOP_DB_URL='jdbc:mysql://127.0.0.1:3306/xzxg_shop_java?useSSL=false&serverTimezone=Asia/Hong_Kong' \
SHOP_DB_USERNAME=root SHOP_DB_PASSWORD=... \
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

- `application.yml` 默认 `spring.flyway.enabled=true`、`baseline-on-migrate=false`。
- 空库启动后 Flyway 创建 23 张表和 `flyway_schema_history`。
- 演示数据不在 V1 中。`local` profile 打开 `shop.seed.demo-enabled=true`，启动后执行
  `db/seed/local-demo.sql`（10 条 `INSERT IGNORE`，与 Go 的 demo 行一致）。
- 非 local 环境默认不写演示数据；这是相对 Go 的有意差异（生产保护）。

## 2. 旧库（Go 运行过的库）接管

不要打开 `baselineOnMigrate`。按以下顺序人工执行：

### 2.1 先备份并在副本上操作

```sql
CREATE DATABASE xzxg_shop_copy;
-- 用 mysqldump 将原库恢复到副本，后续验证全部针对副本
```

### 2.2 schema 核对

必须存在以下 23 张表：

```text
accounts agent_prompt_publish_records agent_prompts agent_runs agent_trace_events
auth_tokens cart_items categories chat_sessions coupons knowledge_chunks
knowledge_documents merchants order_items orders payments product_reviews
product_skus products promotion_rules stored_files user_coupons user_messages
```

必须存在的兼容列（`ensureAccessSchema` 会补的列）：

```text
cart_items.account_id
chat_sessions.account_id, summary, message_count, last_message_at, pinned_at,
               deleted_at, updated_at
user_messages.account_id
agent_runs.account_id, content, blocks_json, followups_json, segments_json
orders.order_no, discount_amount, pay_amount, payment_deadline_at, paid_at,
       closed_at, completed_at, cancel_reason
accounts.avatar_url, phone, email, deleted_at
knowledge_documents.source_url, content_hash, metadata_json
```

必须存在的索引/唯一键：

```text
cart_items        idx_cart_items_account_id, uk_cart_account_product_sku
chat_sessions     idx_chat_sessions_account_id
user_messages     idx_user_messages_account_id, uk_user_messages_client_message
agent_runs        idx_agent_runs_account_id, uk_agent_runs_message
orders            uk_orders_order_no, idx_orders_payment_deadline_at
accounts          idx_accounts_status
knowledge_documents idx_knowledge_documents_content_hash
```

必须**不存在**的旧索引：

```text
cart_items.uk_cart_product_sku
```

可直接用 `information_schema` 生成核对清单：

```sql
SELECT table_name, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('accounts','auth_tokens','cart_items','chat_sessions','user_messages',
                     'agent_runs','agent_trace_events','agent_prompts',
                     'agent_prompt_publish_records','orders','payments','order_items',
                     'products','product_skus','categories','merchants',
                     'promotion_rules','coupons','user_coupons','product_reviews',
                     'knowledge_chunks','knowledge_documents','stored_files')
ORDER BY table_name, ordinal_position;

SELECT table_name, index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
ORDER BY table_name, index_name, seq_in_index;
```

### 2.3 数据修复（在副本上先跑）

Go 启动时会执行以下回填；Java 不会在启动时自动执行，需人工审查后在副本执行：

```sql
UPDATE cart_items      SET account_id = 'acct_user_001' WHERE account_id = '';
UPDATE chat_sessions   SET account_id = 'acct_user_001' WHERE account_id = '';
UPDATE user_messages   SET account_id = 'acct_user_001' WHERE account_id = '';
UPDATE agent_runs      SET account_id = 'acct_user_001' WHERE account_id = '';
UPDATE user_messages   SET client_message_id = CONCAT('server_', message_id)
                       WHERE client_message_id = '';
UPDATE orders          SET order_no = order_id WHERE order_no = '';
UPDATE orders          SET pay_amount = total_amount
                       WHERE pay_amount = 0 AND discount_amount = 0;
UPDATE orders          SET paid_at = COALESCE(paid_at, created_at)
                       WHERE status = 'pending_ship';
```

重复幂等键修复（执行前先查数量并留档）：

```sql
SELECT account_id, session_id, client_message_id, COUNT(*) AS n
FROM user_messages
WHERE client_message_id <> ''
GROUP BY account_id, session_id, client_message_id
HAVING n > 1;

-- 与 Go 相同的修复：保留最早一条，其余加 _dup_<message_id>
UPDATE user_messages um
JOIN (
  SELECT message_id FROM (
    SELECT message_id,
           ROW_NUMBER() OVER (
             PARTITION BY account_id, session_id, client_message_id
             ORDER BY created_at, message_id
           ) AS rn
    FROM user_messages
    WHERE client_message_id <> ''
  ) ranked
  WHERE rn > 1
) dup ON dup.message_id = um.message_id
SET um.client_message_id = CONCAT(um.client_message_id, '_dup_', um.message_id);
```

`products` 的 demo 文案回填（`p_001`/`p_002`）属于演示数据，迁移时不自动执行；
如旧库确实缺少这些字段，单独评估。

### 2.4 显式 baseline

schema 与数据核对通过后，才把 V1 标记为已执行：

```bash
flyway -url="jdbc:mysql://host:3306/xzxg_shop_copy" -user=... -password=... \
       -baselineVersion=1 -baselineDescription="go-baseline-cabd0a1b" baseline
flyway -url="jdbc:mysql://host:3306/xzxg_shop_copy" -user=... -password=... migrate
```

或者用 Flyway Maven 插件执行同样的命令。baseline 之后 `migrate` 应为 no-op。

### 2.5 接管验收

- 23 张表行数与备份一致（允许因回填变化的字段单独记录）。
- 唯一键/索引齐全，旧 `uk_cart_product_sku` 不存在。
- 旧账号可登录（bcrypt 与历史 SHA-256），旧 token 可查询（P2 实现后验证）。
- 应用启动时 Flyway 不报校验错误，且不重复写 demo 行。

## 3. 当前验证状态

本机没有 Go、MySQL 服务端或运行中的 Docker，因此上述流程**尚未实际执行**。
V1 只做了静态核对：23 张表、0 条 INSERT、表名与列名来自
`backend/migrations/001_mysql_schema.sql` 和 `ensureCommerceV3Tables`。
拿到隔离 MySQL 后必须先跑空库初始化和副本接管，再更新
`MIGRATION_STATUS.md` 的验收结果。
