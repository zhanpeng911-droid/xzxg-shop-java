# xzxg-shop：Go 后端迁移 Java 实施方案

- 方案日期：2026-11-02（Asia/Hong_Kong）
- 项目：[LYP-leo/xzxg-shop](https://github.com/LYP-leo/xzxg-shop)
- 源码基线：`main@cabd0a1b01abac05f666c522d238c6c1ce092815`
- 交付对象：负责实施和验证的开发 agent
- 本次交付：迁移方案及路由注册基线；尚未实施 Java 代码、安装依赖、启动服务或运行测试。
- 本地方案目录不代表已存在项目 checkout。开发时优先复用已有源码目录；需要获取源码时另选合适目录，避免向非空方案目录直接执行 `git clone ... .`。

## 1. 目标、范围与完成定义

在保留现有 Web 后台、Android 客户端和业务数据的前提下，用 Java 后端替换现有 Go 后端。目标是功能、客户端协议和数据兼容，交付可在本地独立运行的 Java 系统。

### 1.1 本轮范围

1. 覆盖 `backend/cmd/api` 与 `backend/src` 的全部现有后端能力。
2. 为 `backend/cmd/import-products` 提供 Java 数据导入命令。
3. 保留用户、商家、管理员三类角色及所有现有业务入口。
4. 保留完整 Agent 主链路、多轮记忆、工具/skill 策略、结构化输出和流式响应。
5. 保留知识入库、关键词/向量混合检索、图片检索、语音识别与 TTS。
6. 保留 Nacos 配置、数据库 Prompt 发布机制、风控、链路追踪和质量测评接口。
7. 让 `quality/evals` 的现有 Node.js 脚本能够通过切换 API 地址验证 Java。
8. 提供构建、配置、数据库接管、本地运行和回退说明。

源码基线有 **49 个 Go 文件，其中 13 个是测试文件**。已提取 **87 条 HTTP 路由注册**；部分注册是前缀分发，必须继续展开其真实子动作。见同目录的 [MIGRATION_API_BASELINE.csv](MIGRATION_API_BASELINE.csv)。

### 1.2 本轮边界

- Web 保留 React/TypeScript；`android-native` 本来就是 Java，继续保留。
- 原 Go 源码保留为行为对照，最终 Java 运行、导入和部署不得依赖 Go 二进制。
- 初期采用一个 Spring Boot 应用，按功能模块组织。
- 保留 MySQL、Milvus、MinIO、Nacos 和现有业务 ID；无需更换存储产品。
- 保留模拟支付。不接真实支付、退款网关，不新增业务功能。
- 不顺带实现优惠实际抵扣、库存返还、微服务拆分或前端重构。
- 只在隔离的本地/测试环境实施和验证；生产切换、真实数据修改、发布、push、创建提交或分支另按明确授权处理。
- 不把阶段汇报当作停止点。依次完成方案内的本地实施和相关验证；外部服务缺少配置时继续完成独立任务，最终准确列出缺口。

### 1.3 完成定义

同时满足以下条件才可称“迁移完成”：

- 路由和功能覆盖清单无未解释缺项，无只返回占位内容的业务接口。
- REST、POST SSE、WebSocket、二进制音频及文件访问均通过对应契约验证。
- 新库初始化和既有 Go 数据库副本接管都通过。
- Go 的关键测试场景已转为有效 Java 测试，交易并发和 Agent 副作用有独立验证。
- 未修改业务客户端的核心调用方式，Android 和 Web 关键流程可联调。
- 离线确定性测试通过；真实外部服务验证结果与未验证项分别列出。
- 提供可执行 JAR、构建及运行说明、迁移报告和差异清单。
- 缺少真实模型或语音密钥时只能报告“离线验证完成，外部联调未完成”，不得宣称全量验收通过。

## 2. 已核实的源码事实与迁移决策

源码优先级：固定基线源码及实际调用方 > 运行样本 > README/旧设计文档。文档冲突需登记，不能默默选择更方便的解释。

| 项目 | 基线事实 | Java 迁移决策 |
|---|---|---|
| Web 与数据库框架 | 原生 `net/http`、`database/sql`、大量手写 SQL | Spring MVC + JDBC；按功能拆 Repository 与 Service，保留关键 SQL 语义 |
| API 字段 | 商品 DTO 有 `productId/stockStatus`，订单、会话等有 `order_id/session_id` | DTO 逐字段明确 JSON 名称，禁止全局命名策略改变历史字段 |
| 响应结构 | 成功通常直接返回业务根对象；错误为 `code/message/request_id` | 不新增全局 `data/code/msg` 响应包装 |
| 密码 | bcrypt，以及成功登录后升级的历史 SHA-256 hex | 兼容已有密码；新密码用 bcrypt，保留升级行为 |
| 登录令牌 | 随机不透明 Bearer Token，新令牌存 SHA-256 摘要；旧查询还兼容明文 token | 复用 `auth_tokens` 数据与期限，不改成 JWT；不记录或返回数据库摘要 |
| 数据库 | 基础 SQL 有 22 张表，Go 启动还做补列、索引、回填和额外建表 | 以最终运行 schema 为准，包含动态创建的 `stored_files`；不能只搬 `001.sql` |
| Prompt | `agent.prompt.*` 优先读数据库已发布内容，未命中用代码默认值 | Prompt 数据库优先级保持一致；应用配置继续走 Nacos/默认值 |
| AI 调用 | HTTP 调用外部模型，自写 ReAct 协议 | 首轮用 Java HTTP 适配器保留执行器行为 |
| 图片检索 | 本地 64 维 RGB 直方图，以及外部图片 embedding 两套策略 | 两套都覆盖；不能以更换模型或维度代替迁移 |
| Redis | Compose 声明 Redis，当前 HTTP 限流在进程内 | 不把接入 Redis 变成首轮前置条件，保持原有限流行为 |
| 支付/优惠 | 支付为 mock；下单优惠金额固定为 0 | 作为已有业务边界登记，迁移时不伪称已接真实支付/完整优惠结算 |
| 测评 | 脚本中既有自动检查，也有缺少规则的用例 | 逐例区分已评分、未评分、跳过和失败，不能只展示一个总通过率 |

主要证据：
[后端入口](https://github.com/LYP-leo/xzxg-shop/blob/cabd0a1b01abac05f666c522d238c6c1ce092815/backend/cmd/api/main.go)、
[HTTP 接口与序列化](https://github.com/LYP-leo/xzxg-shop/blob/cabd0a1b01abac05f666c522d238c6c1ce092815/backend/src/httpapi/server.go)、
[领域 DTO](https://github.com/LYP-leo/xzxg-shop/blob/cabd0a1b01abac05f666c522d238c6c1ce092815/backend/src/domain/types.go)、
[MySQL 实现](https://github.com/LYP-leo/xzxg-shop/blob/cabd0a1b01abac05f666c522d238c6c1ce092815/backend/src/store/mysql.go)。

## 3. 目标技术栈与模块结构

### 3.1 默认选型

| 能力 | 默认方案 | 说明 |
|---|---|---|
| 语言 | Java 21 | 保持统一编译和运行版本 |
| 应用 | Spring Boot 4.1.2 | 2026-11-02 官方文档确认支持 Java 21；开发开始时确认仓库无已有 Java 约束，并锁定依赖 |
| 构建 | Maven + Maven Wrapper | 提供 Windows/Unix 启动入口；版本需满足 Boot 要求 |
| HTTP | Spring MVC | REST、POST SSE、文件和音频响应 |
| SQL | JdbcTemplate / NamedParameterJdbcTemplate | 最大限度复用 SQL；复杂事务使用 TransactionTemplate |
| 鉴权 | Spring Security | 接入自有 token 存储；可用自定义 OpaqueTokenIntrospector 或兼容过滤器 |
| 数据迁移 | Flyway + 显式接管流程 | 默认关闭 baselineOnMigrate；空库与旧库分别验证 |
| 模型/向量 HTTP | JDK HttpClient + 独立适配器 | 保留供应商扩展字段与现有 Milvus REST schema |
| 对象存储 | MinIO Java SDK | 保留 bucket/object key；SDK 版本在 P0 锁定并验证当前服务端兼容性 |
| 测试 | Boot 管理的 JUnit、MockMvc、MySQL Testcontainers、HTTP/WS stub | SSE 与 WS 的流行为用真实本地端口测试 |
| Spring AI | 可选，首轮不作为必要依赖 | 官方 2.0.x 支持 Boot 4.0/4.1；引入前证明不会改变已有 Agent 与 Milvus schema |

Boot 4 默认采用 Jackson 3；核心 API 包与 Boot 3 不同，注解仍在 `com.fasterxml.jackson.annotation`。必须按已锁版本文档编写依赖和序列化代码。不要从旧教程直接复制依赖、import 或自动配置。

### 3.2 建议目录

```text
backend-java/
  pom.xml
  mvnw / mvnw.cmd / .mvn/
  src/main/java/com/xzxg/shop/
    ShopApplication.java
    common/        # 错误响应、请求上下文、时间和基础设施边界
    auth/          # 登录、token、角色与权限
    account/       # 用户资料、头像、注销
    catalog/       # 商品、SKU、类目、商家
    cart/          # 购物车
    order/         # 订单、模拟支付、履约
    promotion/     # 活动、优惠券、优惠预览
    review/        # 评价与商家回复
    agent/         # planner、memory、ReAct、tools、skills、输出状态机、run
    retrieval/     # 分块、关键词、向量、排序与图片 embedding
    ingest/        # 非结构化内容入库
    files/         # MinIO、上传、下载与资源访问
    speech/        # 实时识别代理、讯飞/豆包 TTS
    admin/         # 管理操作、观测与测评报告
    config/        # Nacos、默认配置、Prompt 发布
    cli/           # 商品导入及显式维护命令
  src/main/resources/
    application.yml
    application-local.yml
    application-test.yml
    db/migration/
    prompts/       # UTF-8 默认 Prompt，保留原文本与模板占位符
  src/test/
docs/migration/    # 实施过程中新增的基线、状态、差异和验证证据
deployments/      # 新增 Java 本地部署配置，保留原中间件配置
```

采用按功能划分的 Controller / Service / Repository。不要把原 `mysql.go` 改成一个几千行的 Java Store，也不要在 Controller 内承载 Agent 状态机。工具与直接 HTTP 接口应复用业务 Service，防止两套交易逻辑漂移。

## 4. 必须保持的兼容契约

### 4.1 REST、角色与 JSON

- 路径前缀保持 `/api/v1`；保留冒号动作、PATCH、DELETE 和前缀路由的真实子路径。
- 登录返回根对象 `token`；创建会话返回根对象 `session_id`。现有测评脚本直接读取这两个字段。
- 保留 HTTP 状态码、业务错误 code、中文 message、`X-Request-ID` 和错误根对象结构。
- 逐字段核对 `omitempty`、null、空列表、字段缺省及分页语义；不全局替换为“统一格式”。
- 金额对外保持原有字符串与小数位格式；内部采用 BigDecimal 和明确舍入规则。边界差异写入差异清单，不照搬浮点误差。
- DATETIME 的时区、Go time.Time 的 RFC3339 JSON、零时间及可空时间需用实际样本确定，禁止默认使用 JVM 所在时区。
- 注册字符长度等 Go rune 逻辑用 Unicode code point 对齐，不能直接用 Java UTF-16 length。
- 保留用户、商家、管理员权限边界与资源归属检查。管理员不自动继承用户购物车/会话身份。
- 令牌有效期、旧密码升级、注销失效、`active/risk` 状态的认证与风控分别验证。
- 评估路由需检查 handler 内权限，不能只照抄 middleware 的前缀判断，也不能把未列出的路径全局放行。

### 4.2 POST SSE 与消息幂等

接口：
`POST /api/v1/agent/sessions/{session_id}/messages:stream`

- 保留请求体 `client_message_id/content/attachments` 和 Bearer 认证。
- 客户端应稳定生成幂等键；源码允许缺失时生成 `server_<message_id>`，首轮保留该兼容行为。缺失键的重复请求本来不保证去重。
- 用户消息唯一范围为 `(account_id, session_id, client_message_id)`；run 唯一范围为 `(account_id, message_id)`。并发重复提交只能启动一次执行及副作用。
- 响应为 `text/event-stream; charset=utf-8`；每帧保留 `event: <type>`、`data: <JSON>`、空行分隔，JSON 内仍有 `type`。
- 事件包括 `message_start/status/thinking_delta/text_delta/content_delta/block_delta/followups/error/message_end`；以源码实际生成路径为准，不要求每条流都出现全部类型。
- 文字、商品、引用、比较表、购物车、订单、操作入口等结构化块均须保留。
- 保留 `content/blocks_json/followups_json/segments_json` 的持久化；thinking step 按 ID 更新。历史重开后文本和卡片的相对顺序应正确。
- 正常流以 message_start 开始、message_end 收尾。并发思考摘要可交错，测试比较必要偏序和最终 segments，避免强求所有并发事件总顺序完全一致。
- 重复提交的既有协议为 `message_start → error(code=duplicate_message) → message_end`，返回同一个 run_id；不重放旧回答，不重新调用工具，不改变旧 run 状态。
- 取消接口保留 `POST /api/v1/agent/runs/{run_id}:cancel`，状态拼写为 `canceled`。
- Spring Security 的 ASYNC/ERROR dispatch 处理不能使流执行中途再次返回登录错误。

### 4.3 Agent 行为

- 保留风险检查、附件查询规范化、多轮记忆、一级 route、细分 intent 和按角色选择模型的顺序。
- 读取 `center.go`、`model_roles.go` 和 `runtime.go` 实际映射；不要简化成一个固定模型或两个意图。
- 保留最多 6 步 ReAct、解析失败限制、`tool_call/skill_call` JSON、`<final>...</final>` 同次调用流式输出和兜底路径。
- 子意图工具/skill 白名单必须在执行代码校验，不能只写在 Prompt。
- 保留商品检索的相关性过滤与商品 ID 白名单；没有依据时不能猜测 ID 加购。
- 保留 non-guide 的确定性加购/结算路径。历史记忆仅协助指代商品，不能触发历史“加购/下单”动作。
- 保留结构化标签和文字清理状态机，覆盖标签被任意 token 边界截断的情形；禁止用整段文本正则代替增量解析。
- 默认 Prompt 从 Go 常量提取到 UTF-8 资源时保留原文、工具名称、模板变量及选择规则。
- `agent.prompt.*` 读取数据库 active/published 内容，缺失时采用原默认值；不要把 Nacos 中旧 Prompt 值覆盖数据库。
- 无模型 key 的本地规则兜底须可用，但不得把兜底结果作为真实 AI 效果验收证据。

### 4.4 数据、向量与资源

- 保留表名、主键、关联字段、JSON 数据、索引语义和已有业务 ID；不得要求清库。
- 基线表分组：账号 `accounts/auth_tokens`；目录 `merchants/categories/products/product_skus`；交易 `cart_items/orders/order_items/payments/promotion_rules/coupons/user_coupons/product_reviews`；会话与 Prompt `chat_sessions/user_messages/agent_runs/agent_trace_events/agent_prompts/agent_prompt_publish_records`；知识 `knowledge_documents/knowledge_chunks`；文件 `stored_files`。
- 以上共 23 张运行表，仅作初始清单；P0 还需对照实际运行后的列、索引和种子行为。
- Milvus 保留 collection、主键、向量字段、维度、metric、输出字段和原 embedding 模型。默认不自动建集合、不重新生成已有向量。
- 直接使用既有 Milvus REST 协议；Spring AI 默认 VectorStore schema 不能视为天然兼容。
- 本地直方图保持 RGB 各 4 桶、索引 `ri*16+gi*4+bi` 与总像素归一化；透明像素还要对齐 Go RGBA 的预乘语义。
- 覆盖 JPEG/PNG/GIF/WebP。Java 对 WebP 的支持需核实插件与依赖，不得静默丢掉格式支持。
- 文件元数据、bucket、object_key 与原访问 URL 的解释方式保留；下载保留所有者/管理员权限，服务端 MIME 检测及上传限制不弱化。
- 配置质量数据目录、报告目录、商品图片资源目录与头像持久化目录，避免新工作目录使后台测评或图片 404。
- 保留 Nacos namespace/group/data_id、配置 domain 划分、刷新缓存和不可用时的降级；敏感值继续脱敏。

### 4.5 本轮明确处理与延期处理的差异

| 类别 | 处理 |
|---|---|
| 迁移执行器可靠性 | 实现真正的上游取消、超时/断连资源释放、单次终态和有限独立清理任务；正常结果先持久化再发送最终结束事件 |
| 已提交业务副作用 | 取消后停止后续操作；已经提交的加购/下单不假装撤销，保留可追踪结果 |
| 原取消实现疑点 | 源码阅读发现取消回调可能未终止模型流、断连 context 可能使状态回写失败；尚未实测。P0 用 stub 复现并登记，Java 按上述明确目标验证 |
| 业务已有缺口 | 模拟支付、优惠实际抵扣、取消/超时库存返还、定时关单、退款售后流程不在本轮扩充；记录原行为与限制 |
| 原导入命令的清理动作 | 硬编码历史分类 DELETE 不进入普通启动/普通导入；单独输出可审查的维护步骤，真实数据执行需明确授权 |
| 其他新发现问题 | 记录严重性和影响，优先保障迁移正确；不能把修复无关问题变成默认扩大范围 |

交易、权限和数据保护不能为了“对照一致”而削弱。涉及可见业务变化时，单独列出变更、测试与兼容影响。

## 5. 阶段计划

每阶段在开始时阅读指定源码及已锁版本官方文档；实施后留下修改文件、验证命令、实际结果和剩余事项。阶段输入不完整时先补可独立完成的内容。

### P0：源码、文档与行为基线

**输入/参考**：本方案、路由 CSV；`README.md`、`backend/go.mod`、`cmd/api/main.go`、`httpapi/server.go`、`domain/types.go`、`store/mysql.go`、Android `ApiClient.java`、`frontend/src/api`；第 8 节官方文档。

**工作**：
- [ ] 确认实际 checkout、当前 commit、工作区已有改动和适用 AGENTS；保留他人改动。若基线变化，记录增量并更新清单。
- [ ] 创建 `docs/migration/MIGRATION_STATUS.md`、`CONTRACT_MATRIX.md`、`KNOWN_DIFFERENCES.md` 与版本决策记录。
- [ ] 将 87 条注册展开成实际 method/path/action 清单；记录角色、请求、响应、错误、SSE/WS/二进制类型及客户端调用位置。
- [ ] 锁定 JDK/Boot/Maven 与必要依赖，确认实际 starter、Jackson 包和工具 API；已有项目约束优先并记录选型变化。
- [ ] 检查运行配置和外部地址。仅使用隔离 MySQL schema、测试 bucket/collection/Nacos namespace；模型、语音默认使用 stub。
- [ ] 在隔离环境运行 Go 初始化并导出最终 schema，覆盖基础 SQL、ensureAccessSchema 和 ensureCommerceV3Tables；将 demo seed 与 schema 分开登记。
- [ ] 提取有代表性的 REST/SSE/图片向量、密码/token、状态机和签名样本；敏感内容脱敏。
- [ ] 阅读 13 个 Go 测试及现有评估脚本，建立迁移测试映射。检查评估是否会写购物车、订单或注册用户。
- [ ] 记录 Go 取消/断连疑点与交易已有边界，区分源码事实、推断和已复现结果。

**验收**：清单中的每个功能有源码定位；schema 和序列化有样本；允许使用的 API 有文档来源；无法运行的基线检查明确列出原因。

**防偏离**：不凭 README 缩小范围；不运行指向公网演示地址或真实数据的测试；不将本地旧服务当作已验证基线。

### P1：Java 工程、数据库和配置基础

**依赖**：P0。

**参考**：`cmd/api/main.go`、`store/mysql.go:Migrate/ensureAccessSchema`、`migrations/001_mysql_schema.sql`、`configcenter/center.go/nacos.go`；官方 JDBC、事务、Flyway 与 Boot 文档。

**工作**：
- [ ] 建立 `backend-java`、Maven Wrapper、测试 profile、可执行 JAR。
- [ ] 实现健康检查、请求 ID、错误 JSON、CORS、请求体限制、可信代理和限流基础；按基线处理各接口大小限制。
- [ ] 建立数据库连接与功能 Repository。用最终 schema 编写新库迁移。
- [ ] 设计旧库接管：在副本核对 schema 后显式 baseline；缺列/缺索引先通过可审查的无损升级补齐，不直接跳过。
- [ ] 不启用 Hibernate 自动改表，不自动删除历史数据；重复幂等键等历史修复先产出副本验证与影响报告。
- [ ] demo seed 仅在显式本地配置执行，保留需要的演示行为，生产模式不自动写入。
- [ ] 完成 Nacos 适配、默认配置、刷新缓存、生产配置校验、秘密脱敏；整理模型/存储/语音/资源路径的配置映射表。
- [ ] 建立数据库 Prompt 读写、草稿、发布记录及默认资源加载基础，后台端点在权限模块完成后开放。

**验收**：`mvnw verify` 可实际执行已配置测试；新库可初始化；旧库副本接管后数据保留；MySQL 必需依赖失败与 Nacos 可降级行为有测试。

**防偏离**：禁止只复制基础 SQL；禁止自动 baseline 非空未知库；禁止初始化时覆盖已发布 Prompt 或重新计算全量 embedding。

### P2：认证、账号与商品目录

**依赖**：P1。

**参考**：`httpapi/middleware.go:routePolicy`、`server.go:handleLogin/handleRegister/verifyPassword`、`store/mysql.go` 的账号、token、商品部分；Spring Security/JDBC 官方例子。

**工作**：
- [ ] 实现 bcrypt/历史 SHA-256 密码、登录升级、随机 token、摘要存储和旧 token 查询兼容。
- [ ] 实现登录、注册、退出、当前身份、资料、联系方式、头像、注销。
- [ ] 实现类目树、商家、商品列表/详情/SKU，以及商家商品增改删、管理员状态管理。
- [ ] 实现用户/商家/管理员权限及资源归属，保留 account 状态与 Agent 风控的职责区别。
- [ ] 针对混合 JSON 名称、金额字符串、日期、分页、null/缺省写契约测试。
- [ ] 配置 Security 的错误响应和 async dispatch；覆盖 SSE/WS 握手前认证，不能把安全默认 HTML 页面返回给客户端。

**验收**：旧账号/旧 token 在副本可用；跨用户和跨商家访问失败；过期/注销令牌失败；现有 Web 登录和商品浏览 API 无需改协议。

**防偏离**：不要求所有账号重置密码；不换 JWT；不改变商品 DTO 到全局 snake_case；不以 permitAll 解决联调错误。

### P3：购物车、订单、活动、评价与管理

**依赖**：P2。

**参考**：`store/mysql.go:AddCartItem/CreateOrderFromCart/lockSelectedCartItems/reserveOrderStock/PayOrder/CancelOrder/ExpirePendingOrders`、`httpapi/server.go` 相应 handler；TransactionTemplate 官方示例。

**工作**：
- [ ] 完成购物车、勾选、数量、删除及优惠预览。
- [ ] 实现事务内锁购物车、校验并扣减 SKU/商品库存、按商家拆单、写订单/订单项/mock 支付、清理已选项。
- [ ] 业务失败用异常或 setRollbackOnly 明确回滚；不能在 TransactionTemplate 回调返回 false 后仍提交。
- [ ] 保留支付、取消、超时、发货、收货状态流转及权限；状态更新使用条件 SQL/CAS。
- [ ] 完成活动、优惠券、领取、用户券、评价、商家回复及管理端对应功能。
- [ ] 为直接 HTTP 调用和 Agent 工具提供同一业务 Service。
- [ ] 对优惠未实际抵扣、惰性过期等原有限制准确记账，不新增实际收费或隐含退款。

**验收**：库存不足、事务中途失败都无部分写入；重复支付和并发结算不重复完成同一订单/同一批购物车；跨商家拆单正确；管理和用户视图一致。

**防偏离**：不把外部模型调用放入持有数据库锁的事务；不把应用锁当作数据库并发约束；不新增全局 checkout 幂等协议后宣称与原客户端完全一致。

### P4：文件、知识入库、混合检索与图片

**依赖**：P2；与 P3 没有共享实现前置，但按本方案顺序集成。

**参考**：`objectstore/minio.go`、`ingest/unstructured.go`、`rag/*`、`retrievalconfig/config.go`、`imagevector/vector.go`、`store/mysql.go:SearchKnowledgeByPlan`；MinIO/Milvus 官方请求示例。

**工作**：
- [ ] 上传、元数据、所有者鉴权下载、头像和数据集图片资源均可用；资源使用可配置根路径。
- [ ] 文本/HTML/JSON/source_url 入库、清洗、内容 hash、按商家去重、force_reindex、分块行为对齐。
- [ ] 实现关键词召回、向量召回、合并去重、权重排序、同分稳定排序、商品 facet 过滤和摘要截断。
- [ ] 对齐文本 embedding、Milvus 查询/索引任务，保留故障时的 MySQL 检索降级。
- [ ] 实现 local_histogram 与外部图片 embedding，保留文件 ID/object key/URL 解析与图片检索工具输入。
- [ ] 明确索引创建/重建为显式任务；模型/维度变化在独立集合验证，不覆盖旧向量。

**验收**：固定样本分块、排序和 topK 结果符合基线；直方图含透明图片、桶边界及 WebP 样本；文档重复导入行为一致；权限与下载限制未弱化。

**防偏离**：不替换为通用向量检索后忽略品牌/类别规则；不因 SDK 方便重建 collection；不让测试写入现有用户 bucket/collection。

### P5：Agent、SSE、记忆、工具及 Prompt 管理

**依赖**：P3、P4、P1 配置/Prompt。

**参考**：`agent/runtime.go/react.go/llm.go/memory.go/model_roles.go/non_guide_actions.go/tools.go/skills.go/tool_policy.go/trace_schema.go`；`httpapi/server.go:streamAgentRun/writeSSESnapshot`；SseEmitter 与 HttpClient 官方文档。

**工作**：
- [ ] 定义清晰的模型适配器、Planner、MemoryService、ReActExecutor、ToolRegistry、SkillRegistry、RunLifecycleService 和串行事件输出边界。
- [ ] 按原模型角色、Prompt、路由和记忆窗口实现完整主链路，保留小模型与主回答模型配置。
- [ ] 覆盖第 6 节全部 20 个工具与 6 个 skill，并在执行前实施工具权限、账户归属及商品白名单。
- [ ] 实现 non-guide 确定性动作，防止记忆中的历史加购/结算被再次执行。
- [ ] 用状态机处理 final/item/buyer/结构化块与 Markdown fence 的流式边界。
- [ ] 保留会话 CRUD、搜索、置顶、摘要、run、trace、历史 segments 和后台观测。
- [ ] 通过数据库唯一键处理消息/run 幂等，重复流沿用原三事件协议。
- [ ] 实现请求取消、模型流关闭、超时及断连清理。运行终态用 CAS，清理不能依赖已断开的请求上下文。
- [ ] 取消后不再启动新工具；已经提交的副作用保留 trace；正常完成先保存结果，再发送结束事件。
- [ ] 完成 Prompt 草稿/发布/历史选择等实际后台动作，Nacos 配置与数据库 Prompt 保持各自职责。
- [ ] 用可编排的模型 stub 覆盖分支、错误和副作用；测试替身只在测试配置生效。

**验收**：新增高风险测试矩阵通过；真实本地 HTTP 可逐帧收到内容；同一幂等键不会重复加购/下单；客户端重开历史可还原卡片顺序；取消/断连后任务和连接释放。

**防偏离**：不把自写 ReAct 全量换成自动工具调用；不只输出一段聊天文字；不使用静态推荐、假 trace、假成功来填功能缺口。

### P6：语音、向导建议、导入命令和质量后台

**依赖**：P5；语音实现本身可先使用独立 stub。

**参考**：`httpapi/speech.go/speech_test.go/guide_suggestions.go`、`agent/guide_suggestions.go`、`cmd/import-products/main.go`、`docs/ai-guide-backend-protocol.md`、`quality/evals` 及 Android 的 voice/tts/network 模块。

**工作**：
- [ ] 实时语音 WebSocket 保留 PCM 16000Hz、控制帧 start/end/cancel、识别结果/ready/error/closed 等消息。
- [ ] 按源码分别实现讯飞实时识别签名、讯飞 TTS 签名与豆包 TTS HTTP 适配，不共用错误的签名算法。
- [ ] TTS 成功返回正确 MIME 的二进制音频；配置禁用、空文本、超长、超时和供应商失败按原约定处理。
- [ ] 实现页面上下文向导建议、模型与规则兜底。
- [ ] 提供明确的 Java 导入子命令，读取 ECOMMERCE_DATASET_ROOT，保留商品/SKU/文档/chunk ID 和 upsert 行为。
- [ ] 导入只由显式命令触发，不在普通 API 启动时执行历史清理。
- [ ] 实现意图/RAG/商品/图片测评接口、数据集与报告列表/详情；确保路径在 Java/Docker 中有效。
- [ ] 保持现有 Node 测评脚本可用，只有可审查的兼容配置改动。

**验收**：固定时间和参数下签名与 Go 样本一致；stub WS/TTS 正常及错误场景通过；导入可重复运行且数据不重复；后台可查看真实生成的测评报告。

**防偏离**：不把语音接口一律改 JSON；不暴露供应商密钥给客户端；没有真实供应商联调时不能宣称语音效果已验证。

### P7：客户端联调、对照回归与交付

**依赖**：P0–P6。

**参考**：`frontend/src/api`、Android `ApiClient.java/AgentStreamController.java`、全部迁移契约及测试；第 7 节矩阵。

**工作**：
- [ ] 在不同端口和独立数据副本运行 Go/Java，对确定性接口做对照；随机 ID、时间等只按明确规则归一化。
- [ ] 验证 Web 管理员/商家流程，Android 商品→导购→加购→结算→mock 支付→发货→收货→评价流程。
- [ ] 验证多轮商品指代、图片、语音、会话历史、Prompt 发布、trace、风控和测评后台。
- [ ] 运行 Maven verify、现有前端 build，以及适用的原 Node 评估脚本；Go 测试作为基线检查，不能替代 Java 测试。
- [ ] 核对测试是否真的执行、集成测试是否被跳过，输出命令、通过/失败/跳过数量和原因。
- [ ] 有测试密钥且调用预算已明确时做真实模型/语音联调，记录调用配置、数据集、成功率、首 token 与总耗时；不记录密钥。
- [ ] 提供 Java 本地运行配置、Docker 配置、数据目录映射、运行 JAR、导入命令、数据库接管说明与回退步骤。
- [ ] 更新主 README 的实际启动方式；保留原 Go 对照和说明。
- [ ] 输出功能覆盖、已知差异、schema 变更、实测结果、外部未验证项和剩余工作。

**验收**：第 1.3 节完成定义逐项有证据；不存在迁移缺项冒充“后续优化”；生产部署和真实数据切换保持未执行。

**防偏离**：不让两个后端同时写同一测试库来做“公平对照”；不把模型自然语言全文相等作为核心判定；不以构建成功替代功能验收。

## 6. 功能覆盖清单

### 6.1 业务模块

| 模块 | 必须覆盖 |
|---|---|
| 认证与账号 | 登录、注册、退出、身份、资料/联系方式、头像、注销、历史密码/token |
| 商品 | 类目树、商家、列表、详情、SKU、商家增改删、管理员状态管理 |
| 购物车 | 读取、添加、数量/勾选更新、删除、优惠预览 |
| 订单 | 跨商家拆单、库存事务、列表/详情、mock 支付、取消、超时、发货、收货 |
| 活动与评价 | 活动、券列表/领取/用户券、评价、回复、管理员管理 |
| Agent | 会话列表/搜索/置顶/摘要/删除、消息流、run 取消、历史与 trace |
| Prompt 与配置 | 数据库 Prompt 草稿/发布/实际历史操作、Nacos 配置更新/刷新/脱敏 |
| 检索 | 商品/知识关键词与向量召回、排序、图片双策略、入库去重与分块 |
| 文件与资源 | 上传/鉴权下载、头像、数据集商品图片、元数据 |
| 语音 | 实时 ASR WebSocket、TTS 配置/二进制音频、讯飞与豆包适配 |
| 管理与质量 | 用户/商品/商家/订单管理、风控、run/trace、向量状态、测评数据及报告 |
| 运维工具 | Java 商品导入、显式索引任务、schema 初始化/接管、本地部署 |

### 6.2 Agent 工具与 skill

20 个工具以 `agent/tools.go` 为准：

```text
search_products       search_image_products    search_knowledge
get_cart              add_cart_item            update_cart_item
delete_cart_item      checkout                 list_orders
get_order             pay_order                cancel_order
confirm_receipt       preview_discount         list_coupons
list_user_coupons     claim_coupon             list_promotions
list_product_reviews  create_product_review
```

6 个 skill 以 `agent/skills.go` 为准：

```text
navigate_cart  navigate_orders  navigate_products
coupon_help    order_help      after_sales_help
```

保留名称、参数 JSON、结果结构、权限、意图白名单与 trace 表达；skill 导航入口不代表完整售后退款系统已实现。

### 6.3 子路径展开的重点

路由 CSV 是注册清单，不能直接当作完成的 API 规范。P0 特别展开：

- `products/{id}` 下的详情、SKU、评价等实际分支。
- `orders/{id}` 的 GET 与 POST 动作：支付、取消、确认收货、评价等，以 handler switch 为准。
- `agent/sessions/{id}`、`{id}:pin`、`{id}:summarize`、`{id}/messages:stream`。
- `agent/runs/{id}:cancel`、`{id}/trace`。
- Prompt 的 URL 编码 key、草稿更新、发布、发布记录/历史参数。
- 商家评价回复、订单发货、商品状态等动作后缀。
- 文件、报告、静态资源的真实资源定位与错误处理。

## 7. 验证矩阵与实施命令

### 7.1 必须有证据的测试

| 类别 | 关键场景 | 通过标准 |
|---|---|---|
| JSON 契约 | 商品 camelCase、订单 snake_case、根对象、错误/分页、可空值 | 客户端解析无需改业务代码；固定样本符合基线 |
| 账号 | bcrypt、历史 SHA-256、Unicode 长度、旧/新 token、到期/注销 | 兼容历史数据，新写入不降低保护 |
| 权限 | 三角色、跨账号会话/文件/订单、跨商家操作 | 未授权及越权按契约失败 |
| 数据迁移 | 空库、Go 最终 schema 副本、旧结构差异 | 数据保留、索引完整、迁移可重复验证 |
| 交易 | 库存不足、中途 SQL 失败、并发扣库存/支付/结算 | 不超卖、不部分提交、不重复完成同一笔操作 |
| 幂等 | 顺序与并发重复消息、相同键在不同账号/会话 | 正确范围内仅一个 run/一次工具副作用 |
| 重复 SSE | running/completed run 的重复请求 | 同 run_id，三事件协议，不重执行 |
| 流式解析 | token 在标签任意字符处分割、转义字符、中文、fence | 正文/块正确，不泄露协议文本 |
| 流生命周期 | 正常、上游错误、显式取消、超时、网络断连 | 终态不被覆盖，无遗留任务；可连接时按约定收尾 |
| 记忆 | 指代前次商品、无关历史、历史含加购/结算 | 正确关联商品，不重放历史动作 |
| Agent 安全 | 不允许工具、伪造商品 ID、未知 skill | 被执行层拒绝，无业务副作用 |
| RAG | 关键词、向量故障、品牌/类别、同分排序、空结果 | 固定输入输出可解释，规则不缺失 |
| 图片 | 64 维直方图、透明像素、桶边界、外部维度、WebP | 与基线样本兼容，旧向量可检索 |
| 文件/入库 | 大小/MIME、所有者、内容去重、force_reindex | 权限与元数据正确，重复行为符合契约 |
| 配置/Prompt | Nacos 失败/刷新、密钥脱敏、发布内容优先、默认兜底 | 不覆盖用户编辑，真实生效路径正确 |
| 语音 | 固定签名、PCM 帧、start/end/cancel、二进制 MIME、禁用/超时 | stub 契约通过；真实联调另标状态 |
| 导入/报告 | 重复 upsert、资源根目录、现有报告格式 | ID 稳定、不重复，后台可读取 |
| 客户端 | Web 管理/商家、Android 用户关键流程 | 不出现仅后端自测通过的协议断层 |

确定性契约及核心副作用用 stub 固定输入，要求全部通过。真实模型结果比较意图、召回、工具执行和任务成功，不比较回答全文。首轮基线未测出的指标不得写成既有事实；外部效果没有证据时保持未验证。

### 7.2 命令模板

下列命令供实施阶段使用，本次未执行。运行前检查配置均指向测试环境；PowerShell 示例：

```powershell
# 在 backend-java 目录
.\mvnw.cmd verify
.\mvnw.cmd package
java -jar .\target\<实际产物名>.jar --spring.profiles.active=local

# 在 frontend 目录
npm run build

# 在仓库根目录；端口仅为 Java 测试环境示例
$env:API_BASE_URL = 'http://127.0.0.1:8082/api/v1'
$env:EVAL_REPORT_DIR = 'quality/reports/java-migration'
node quality/evals/run_intent_eval.mjs quality/data/eval/intent_cases.jsonl
node quality/evals/run_rag_recall_eval.mjs quality/data/eval/rag_recall_cases.jsonl
node quality/evals/run_product_search_eval.mjs
node quality/evals/run_image_search_eval.mjs quality/data/eval/image_search_cases.jsonl
node quality/evals/run_memory_retrieval_eval.mjs quality/data/eval/memory_retrieval_cases.jsonl
node quality/evals/run_agent_e2e.mjs quality/data/eval/agent_e2e_20_scenarios.jsonl
```

命令中的脚本默认参数、登录变量和测试数据实际要求，执行前读取对应脚本确认。业务评估会产生数据，限隔离环境运行。没有真实模型配置时，明确记录本轮测到的是兜底或 stub。

`verify` 必须配置为实际运行所需的单元/集成测试；不得把未启用的集成测试或无 Docker 的自动 skip 当成通过。外部服务不可用时报告具体未验证项。

## 8. 官方文档与允许使用的 API

以下为方案阶段已核对的来源；开发时按已锁定版本阅读对应示例，避免凭记忆拼 API。

| 主题 | 已核对 API / 规则 | 文档 |
|---|---|---|
| Boot/JDK/Maven | Boot 4.1.2 支持 Java 17–27；Maven 至少 3.6.3 | [System Requirements](https://docs.spring.io/spring-boot/system-requirements.html) |
| Boot 4 依赖/JSON | webmvc/jdbc/flyway starter；Jackson 3 包变化 | [Boot 4 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) |
| SSE | SseEmitter(Long)、event()、send(SseEventBuilder) 与生命周期回调 | [MVC SSE](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)、[Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html) |
| 自有 token | OpaqueTokenIntrospector.introspect(String)，允许查询自有存储 | [Opaque Token Introspection](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/opaque-token.html#oauth2resourceserver-opaque-introspector) |
| SQL | JdbcTemplate.query/queryForObject/update、RowMapper | [JDBC Core 示例](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html) |
| 事务 | TransactionTemplate.execute、TransactionStatus.setRollbackOnly | [Programmatic Transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html) |
| Flyway 接管 | baselineVersion 之前的迁移被视为已执行；baselineOnMigrate 默认保持关闭 | [Baseline](https://documentation.red-gate.com/flyway/reference/commands/baseline)、[baselineOnMigrate](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting) |
| 模型 HTTP | HttpClient.send/sendAsync，响应流必须可关闭 | [Java 21 HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html) |
| MinIO | MinioClient、PutObjectArgs、GetObjectArgs；GetObjectResponse 使用后关闭 | [Java SDK Javadoc](https://minio-java.min.io/io/minio/MinioClient.html) |
| Milvus 2.5 | POST /v2/vectordb/entities/search，collectionName/data/annsField/outputFields | [REST Search](https://milvus.io/api-reference/restful/v2.5.x/v2/Vector%20(v2)/Search.md) |
| 可选 Spring AI | 2.0.x 支持 Boot 4.0/4.1；BOM 管理版本 | [Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html) |
| VectorStore 边界 | 默认 schema 初始化和字段结构不证明兼容项目自定义集合 | [Spring AI Milvus](https://docs.spring.io/spring-ai/reference/api/vectordbs/milvus.html) |

选择自定义认证过滤器时不必为使用 introspector 额外接远程 OAuth 服务；选择 introspector 时要声明实际 resource-server 依赖并明确角色映射。SSE 的 GET 官方示例仅用于了解响应机制，本项目仍用 POST。

## 9. 实施交付物与接续方式

开发 agent 最终应交付：

1. `backend-java` 的完整可运行实现和有效测试。
2. Java 本地部署配置与环境变量模板；模板不得包含真实密钥。
3. 新库初始化及旧库接管文件、schema 对照与无损验证结果。
4. Java 导入命令、显式索引任务与资源目录配置。
5. 按真实子接口展开的覆盖矩阵，指向 Java 实现和测试。
6. `docs/migration/MIGRATION_STATUS.md`：阶段进度、实际修改和下一步。
7. `docs/migration/KNOWN_DIFFERENCES.md`：行为差异、已有业务缺口、待确认事项。
8. `docs/migration/VERIFICATION_REPORT.md`：实际命令、环境、通过/失败/跳过及外部联调状态。
9. README 启动说明、产物位置和回退说明。

单个开发 agent 可按 P0–P7 顺序执行。若另获授权使用多个 agent，可按“认证目录与通用协议 / 交易 / 检索文件 / Agent / 语音工具”划分文件所有权；共享 DTO、schema、配置、POM 和集成由一个负责人协调。并行任务必须告知彼此不独占代码库，不覆盖他人改动。

建议实施期间持续更新状态文件，使新会话可以从具体阶段、失败用例和证据接续，避免重新猜测范围。

## 10. 可直接发给开发 agent 的任务文本

> 请按照《xzxg-shop：Go 后端迁移 Java 实施方案》及同目录 MIGRATION_API_BASELINE.csv 实施迁移。
>
> 目标：保留现有 Web/Android 客户端及数据，用 Java 21 + Spring Boot 单体后端完整替换现有 Go 后端，包括导入命令、Agent、检索、图片、语音、后台、Prompt 与测评。默认技术方案、兼容契约和阶段验收均在方案内。
>
> 从 P0 开始核对实际 checkout、基线和工作区改动，再连续完成后续本地实施与验证。不要只搭骨架、只迁 CRUD、只给聊天接口，或把现有能力改成占位返回。不要在每个阶段结束时停下来重复询问是否继续。
>
> 特别注意：JSON 混合命名、根对象响应、历史密码/token、Go 动态 schema 补丁、消息幂等与重复 SSE、增量标签解析、non-guide 确定性动作、图片双策略及语音签名。工具与 HTTP 复用业务 Service，权限与事务不弱化。
>
> 保留 Go 源码作为对照，Java 最终运行不依赖 Go。不修改无关前端业务，不清库、不删除用户数据、不连接公网演示环境做写入测试；不创建提交/分支、push 或执行生产部署。真实外部联调只使用已明确授权的测试配置和调用预算。
>
> 请持续维护 docs/migration 下的状态、契约、差异和验证报告。遇到缺少密钥等外部阻碍时继续完成其他独立工作，并准确列出未验证项。交付完整本地运行方式、schema 接管说明、实际测试结果与剩余限制；未验收完成时不要宣称全量迁移完成。

---

## 附录：关键源码定位

所有相对路径以项目仓库根目录为基准。下列位置是开发阅读入口，不等于完整实现范围：

| 路径 | 重点 |
|---|---|
| backend/cmd/api/main.go | 生命周期、配置校验、迁移、向量 bootstrap |
| backend/cmd/import-products/main.go | 数据集导入、分类/ID/upsert、历史清理 |
| backend/src/httpapi/server.go | 全部路由、子动作、响应、SSE、密码校验 |
| backend/src/httpapi/middleware.go | 权限、限流、日志、大小限制、代理 |
| backend/src/httpapi/speech.go | WS/ASR、两类签名、TTS 供应商与音频 |
| backend/src/domain/types.go | 字段名、金额/时间、block/event/run 状态 |
| backend/src/store/interface.go | 原业务数据访问边界 |
| backend/src/store/mysql.go | 最终 schema、历史兼容、交易、检索与 Prompt |
| backend/src/agent/runtime.go | 主流程、路由、模型配置、Prompt 优先级 |
| backend/src/agent/react.go | ReAct、取消、标签过滤与增量输出 |
| backend/src/agent/non_guide_actions.go | 确定性动作、本轮问题与历史记忆边界 |
| backend/src/agent/tools.go / skills.go / tool_policy.go | 工具、导航 skill、白名单 |
| backend/src/agent/memory.go / trace_schema.go | 记忆与 trace |
| backend/src/configcenter/center.go / nacos.go | 默认 Prompt/配置、Nacos 协议与降级 |
| backend/src/rag / retrievalconfig | 分块、召回、排序、向量 REST 与参数 |
| backend/src/imagevector/vector.go | 图片直方图与外部 embedding |
| backend/src/ingest/unstructured.go / objectstore/minio.go | 入库与文件存储 |
| quality/evals / quality/data/eval | 实际测评脚本及数据 |
| frontend/src/api | Web 调用契约 |
| android-native/.../network/ApiClient.java | Android REST/POST SSE 客户端 |
| android-native/.../chat/AgentStreamController.java | 客户端取消与流生命周期 |

