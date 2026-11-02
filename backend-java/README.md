# xzxg-shop Java backend

Java 21 + Spring Boot 4.1.2 replacement for the Go backend. It covers auth,
accounts, catalog, cart, orders, promotions, reviews, files, knowledge ingest,
hybrid retrieval, image search, Agent/SSE, prompts, admin operations, evals,
speech adapters, and the product import CLI.

## Requirements

- JDK 21+ (built locally with Temurin 25, compiled for release 21)
- Maven Central access for the first build
- MySQL 8 for real runs/tests (SQL uses JSON columns and window functions)
- Optional: MinIO, Milvus, model/voice keys for external integrations

Maven itself is not required; the wrapper downloads Maven 3.9.11.

## Build and test

```bash
cd backend-java
./mvnw clean verify          # unit/infrastructure tests (Windows: mvnw.cmd)
./mvnw package               # executable jar
```

Integration tests run against a real MySQL schema and are named `*IT`:

```bash
# create an isolated database first
SHOP_IT_DB_URL='jdbc:mysql://127.0.0.1:3306/xzxg_shop_it?useSSL=false&serverTimezone=Asia/Hong_Kong' \
SHOP_IT_DB_USERNAME=root SHOP_IT_DB_PASSWORD=... \
./mvnw -Dtest=P2FlowIT,P3FlowIT,P4FlowIT,P5FlowIT,P6FlowIT test
```

Each `*IT` boots the real servlet stack and exercises HTTP/SSE against MySQL.
They mutate the target database; use a disposable schema.

## Run

```bash
java -jar target/shop-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
curl http://127.0.0.1:8082/api/v1/health
```

`local` enables demo seed rows. Flyway V1 creates 23 tables; legacy database
takeover is manual (`baseline-on-migrate: false`) — see
`../docs/migration/DB_TAKEOVER.md`.

## Product import

```bash
ECOMMERCE_DATASET_ROOT=../quality/data/ecommerce_agent_dataset \
java -jar target/shop-backend-0.1.0-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --shop.cli.import-products=true
```

The import upserts `m_dataset_001`, categories, products, SKUs, knowledge
documents and chunks. It never runs on normal API startup.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8082` | HTTP port |
| `SHOP_DB_URL` / `SHOP_DB_USERNAME` / `SHOP_DB_PASSWORD` | local MySQL | JDBC |
| `SHOP_FLYWAY_ENABLED` | `true` | Run migrations |
| `SHOP_MAX_REQUEST_BODY_BYTES` | `12582912` | Request body cap |
| `SHOP_TRUST_ALL_PROXIES` / `SHOP_TRUSTED_PROXIES` | false / empty | Forwarded-header trust |
| `SHOP_CORS_ALLOWED_ORIGINS` | `*` | CORS origins |
| `SHOP_RATE_LIMIT_ENABLED` / `_LIMIT` / `_WINDOW_SECONDS` | true / 120 / 60 | Fixed-window limiter |
| `SHOP_DEMO_SEED_ENABLED` | `false` | Apply `db/seed/local-demo.sql` |
| `MINIO_ENDPOINT` / `_ACCESS_KEY` / `_SECRET_KEY` / `_BUCKET` | 127.0.0.1:9000 / minioadmin / minioadmin / xzxg-shop-assets | Object storage |
| `VECTOR_ENABLED` | `false` | Milvus retrieval |
| `MILVUS_ADDRESS` / `MILVUS_TOKEN` | http://127.0.0.1:19530 / root:Milvus | Milvus |
| `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` / `EMBEDDING_MODEL` | DashScope / empty / text-embedding-v4 | Text embeddings |
| `IMAGE_EMBEDDING_PROVIDER` | `local_histogram` | `local_histogram` or `dashscope` |
| `AI_BASE_URL` / `DASHSCOPE_API_KEY` / `AI_API_KEY` | DashScope / empty | Agent model |
| `AI_SMALL_MODEL` / `AI_LARGE_MODEL` | qwen3.5-flash / qwen3.7-plus | Model roles |
| `XUNFEI_APP_ID` / `_API_KEY` / `_API_SECRET` | empty | Realtime ASR |
| `XUNFEI_TTS_ENABLED` + keys | false | Xunfei TTS |
| `DOUBAO_TTS_ENABLED` + keys | false | Doubao TTS |

## Implemented surface

- `GET /api/v1/health`
- `/api/v1/auth/*`, `/api/v1/account/*`, `/api/v1/uploads/avatar/*`
- `/api/v1/categories/tree`, `/api/v1/merchants`, `/api/v1/products*`
- `/api/v1/cart*`, `/api/v1/orders*`, `/api/v1/promotions`,
  `/api/v1/coupons*`
- `/api/v1/files*`, `/api/v1/search/image`, `/api/v1/merchant/documents`,
  `/api/v1/merchant/unstructured-ingestions`
- `/api/v1/agent/sessions*`, `/api/v1/agent/runs/*`,
  `/api/v1/agent/guide-suggestions`
- `/api/v1/speech/tts/config`, `POST /api/v1/speech/tts`,
  `GET /api/v1/speech/realtime` (WebSocket when configured)
- `/api/v1/admin/*` for accounts, products, merchants, orders, promotions,
  reviews, configs, prompts, documents, ingestions, agent runs/trace, vector
  status, evals
- `/api/v1/eval/*`

## Known gaps

See `../docs/migration/KNOWN_DIFFERENCES.md`. The important ones: MinIO/Milvus
and voice/model providers are implemented but not live-tested; Nacos is not
wired; LLM streaming is chunked post-completion; some prompt/planner details are
simplified.
