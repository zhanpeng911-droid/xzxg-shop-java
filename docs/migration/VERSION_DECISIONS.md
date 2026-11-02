# 版本与选型决策

- 决策日期：2026-11-02
- 依据：方案第 3/8 节、Go 源码 `cabd0a1b`、本机实际构建与 MySQL 测试

## 1. 已锁定

| 组件 | 版本 | 决策依据 | 验证 |
|---|---|---|---|
| Java 编译目标 | 21（`<java.version>21</java.version>`） | 方案要求 | `mvnw clean verify` 通过 |
| 本机 JDK | Temurin 25.0.4.1 | 本机唯一可用 JDK；release 21 | 构建/启动成功 |
| Spring Boot | 4.1.2 | 方案指定；Central 元数据确认 | 构建/启动成功 |
| 构建 | Maven 3.9.11 + Wrapper 3.3.4 | Boot 4 要求 ≥3.6.3 | wrapper 自动下载并构建成功 |
| Web | `spring-boot-starter-webmvc` | Boot 4.1.2 将 `starter-web` 标记 deprecated | 编译/启动成功 |
| JSON | Jackson 3（Boot 4 默认） | 注解仍在 `com.fasterxml.jackson.annotation` | 混合命名/omitempty 测试通过 |
| SQL | `spring-boot-starter-jdbc` | 保留手写 SQL | 真实 MySQL 通过 |
| 迁移 | Flyway + `flyway-mysql` | 方案要求显式接管 | 空库/接管均实测 |
| 驱动 | `com.mysql:mysql-connector-j` | Boot 4 托管版本 | 真实 MySQL 通过 |
| 密码 | `spring-security-crypto` 7.1.2 | bcrypt，不引入完整 Security 过滤链 | P2 实测 |
| 对象存储 | `io.minio:minio` 9.0.4 | 保留 bucket/object key | 代码完成，未联调 |
| MinIO OkHttp | `okhttp-jvm` 5.5.1 | MinIO 9 的 `okhttp` 坐标是 KMP 元数据 jar | 编译通过 |
| 图片格式 | TwelveMonkeys `imageio-webp` 3.15.3 | Java 原生不支持 WebP | 单测覆盖 PNG/透明；WebP 待样本 |
| WebSocket | `spring-boot-starter-websocket` | 实时 ASR 代理 | 代码完成，未联调 |
| 配置中心 | Nacos v1 HTTP API（无额外 SDK） | 读取/发布 JSON data id + 定时刷新 | 未连接真实 Nacos |
| 测试门禁 | Maven Failsafe（`-DskipITs=false`） | 集成测试显式接入验收命令 | 41 单测 + 9 集成通过 |
| 测试 | JUnit 5 + AssertJ + spring-test | Boot 4 starter-test | 41 单测 + 9 集成 |
| MySQL 集成测试 | 本机 MySQL 8.0.12（隔离 schema） | 无 Testcontainers 依赖 | P2–P6 + AgentPolicy/Idempotency |

## 2. 明确未引入

| 组件 | 原因 |
|---|---|
| Hibernate / JPA / 自动 DDL | 方案禁止自动改表；保留手写 SQL |
| 完整 Spring Security 过滤链 | 自定义 `AuthFilter` 精确复刻 `routePolicy`；bcrypt 单独使用 |
| Spring AI | 默认 VectorStore schema 不保证兼容现有 Milvus 集合 |
| Redis | 原限流为进程内固定窗口 |
| JWT | 保留不透明 token 与 `auth_tokens` 表 |

## 3. 配置默认值（来自 Go 源码）

| 配置 | 默认 | 源码 |
|---|---|---|
| `server.port` | 8082 | 方案测试命令；Go 默认 `:8080` |
| `shop.web.max-request-body-bytes` | 12582912（12 MiB） | `middleware.go:118` |
| `shop.web.trust-all-proxies` | false | `center.go:517` |
| `shop.web.trusted-proxies` | 空 | `center.go:516` |
| `shop.web.cors.allowed-origins` | `*` | `center.go:515` |
| `shop.web.rate-limit.enabled/limit/window` | true / 120 / 60s | `middleware.go:95` |
| `shop.seed.demo-enabled` | false（local=true） | 生产保护 |
| `spring.flyway.baseline-on-migrate` | false | 方案要求显式接管 |
| `shop.files.max-upload-bytes` | 10485760 | `center.go:518` |
| `shop.retrieval.vector.enabled` | false | 本机无 Milvus；Go 默认 true 但连接失败会降级 |
| `shop.speech.tts.xunfei.enabled` | false | `center.go:520` |
| `shop.speech.tts.provider` | xunfei | `center.go:519` |

## 4. 待锁定

- Milvus collection 字段/维度与 REST 客户端的一致性；索引 bootstrap 实现。
- MinIO 9.0.4 与现有服务端版本的兼容性。
- 语音供应商真实密钥下的签名与音频格式。
- WebP 解码样本与透明像素桶边界。
- Node 测评脚本在 Java API 下的实际通过率。

## 5. 变更规则

- 版本升级后重跑 `mvnw clean verify` 与 P2–P6 集成测试并记录输出。
- 改变 CORS、限流、请求上限、错误 code 属于行为变更，必须写入
  `KNOWN_DIFFERENCES.md`。
- 不引入会修改既有表结构或重算向量/Embedding 的自动配置。
