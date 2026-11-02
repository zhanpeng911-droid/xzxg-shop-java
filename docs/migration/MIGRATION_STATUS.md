# 迁移状态

- 更新时间：2026-11-02（Asia/Hong_Kong）
- 源码：`D:\claudecode\shop`，`HEAD = cabd0a1b01abac05f666c522d238c6c1ce092815`
- 交付定位：**Java 基础版 + 关键安全/流式修复**；不是全量迁移验收完成
- 状态口径：
  - **验证通过**：有可重复命令和断言结果
  - **已实现未验证**：代码存在，未连接真实外部服务或未跑真实验收
  - **未实现**：尚未落地

## 1. 验证通过

| 能力 | 证据 |
|---|---|
| 工程构建、Maven Wrapper、可执行 JAR | `mvnw clean verify` |
| 请求 ID、CORS、限流、体积限制、错误对象、可信代理 | `InfrastructureHttpTest` 7 + 单元测试 |
| 密码 bcrypt/历史 SHA-256、token、角色 401/403 | `P2FlowIT` |
| 商品目录、商家商品 CRUD、管理员账号/商品状态 | `P2FlowIT` |
| 购物车、优惠预览、跨商家拆单、mock 支付、发货/收货、评价、券 | `P3FlowIT` |
| 知识入库/去重、关键词召回、图片搜索降级 | `P4FlowIT` |
| SSE 帧序、重复消息三事件协议、历史 blocks/segments、trace、置顶/摘要 | `P5FlowIT` |
| 工具/skill 意图白名单 | `ToolPolicyTest` 6 + `AgentPolicyIT` |
| 商品 ID 白名单（猜测 ID 拒绝、显式/检索 ID 允许） | `AgentPolicyIT` |
| 取消：数据库状态 + 模型 HTTP 请求中止 + 后续步骤停止 | `AgentPolicyIT` |
| 流中途停顿时取消：主动关闭响应流，不等上游恢复 | `AgentPolicyIT.cancellationStopsMidStreamPause` |
| 客户端断连：心跳 + `checkError()` 检测并停止上游 | `AgentPolicyIT.clientDisconnectStopsRun` |
| 真实增量流 + 跨分块 `<final>`/标签解析 | `AgentPolicyIT`（stub SSE） |
| 并发消息/run 幂等、并发加购数量 | `IdempotencyIT` 2 |
| Nacos Go 格式（`configs` 数组）/legacy Map 读取与发布 | `NacosConfigClientTest` |
| Nacos 新增敏感键默认脱敏 | `ConfigServiceTest.newRemoteSecretKeyIsMasked` |
| 图片 bootstrap SQL 列名限定 | `VectorBootstrapIT` |
| 向导建议、测评接口/看板、配置读写、TTS 未配置 501 | `P6FlowIT` |
| 商品导入 294 商品/973 SKU/1121 文档/1962 chunk | 导入命令实测 |
| 空库 Flyway V1、最终 schema 副本显式 baseline | 手动实测 |

验收命令：

```bash
# 单元 + 基础设施
mvnw clean verify

# 单测 + 全部集成测试（需要隔离 MySQL）
SHOP_IT_DB_URL='jdbc:mysql://127.0.0.1:3306/xzxg_shop_it?...' \
SHOP_IT_DB_PASSWORD=... \
mvnw verify -DskipITs=false
```

最近一次结果：

```text
Tests run: 43, Failures: 0, Errors: 0, Skipped: 0        # surefire
Tests run: 12, Failures: 0, Errors: 0, Skipped: 1        # failsafe（Takeover 未提供环境）
BUILD SUCCESS
```

## 2. 已实现未验证

| 能力 | 实现 | 缺什么 |
|---|---|---|
| Nacos 配置持久化/动态刷新 | `NacosConfigClient`、`NacosRefreshJob`、`ConfigService` 驱动 CORS/限流/体积/代理/vector 开关 | 未连接真实 Nacos；刷新周期与 namespace 未实测 |
| Milvus 集合创建/写入/加载/bootstrap | `MilvusClient.ensureCollection/upsert/load`、`VectorBootstrapService` 文本+图片 | 只做了 REST stub 单测；未连接真实 Milvus |
| 真实模型 Agent | OpenAI 兼容 `stream=true`、ReAct、角色模型、Prompt DB | 无真实 Key；stub 只验证协议/策略/取消 |
| 语音 TTS/ASR | 讯飞/豆包签名、WSS/HTTP、WebSocket 代理 | 无密钥，未实测 |
| MinIO 上传/下载 | `ObjectStorage`、`/api/v1/files` | 本机 MinIO 未启动，未做 bucket 往返 |
| 测评报告详情 | 看板扫描 + 报告文件读取 | 无真实报告文件；Node 脚本未对 Java 运行 |
| Web/Android 客户端联调 | 客户端代码存在 | 未构建、未运行真实客户端 |
| Node `quality/evals` 联调 | 脚本存在 | 未对 Java API 执行 |

## 3. 未实现

| 能力 | 说明 |
|---|---|
| Go 全量默认 Prompt / 意图 Prompt 逐字迁移 | 只有 7 个简化默认 Prompt；`agent.prompt.intent.*` 未迁移 |
| 一级模型规划（route/P1–P6 模型判定） | 当前为关键词意图分类，未调用模型规划 |
| `need_summary` 模型流程 | 未实现 |
| `memory_retrieval` 模型流程 | 当前只取最近 5 条对话，未做模型抽取 |
| 真实旧库“缺列”升级验证 | 仅验证最终 schema 副本 baseline |
| 生产部署、真实数据切换 | 明确未执行 |
| 原系统 mock 支付/优惠抵扣/退款售后扩展 | 保持原边界，不新增 |
| 向量新建集合后的增量同步任务 | 只有启动 bootstrap，无持续同步 |

## 4. 本轮新增修复

- 工具/skill 意图白名单在执行层强制，不再只写 Prompt。
- 商品 ID 白名单：模型猜测 ID 被拒绝；用户显式 ID 或本轮检索结果可用。
- 取消：模型 HTTP 请求可中止，工具调用前检查，取消后不再启动后续步骤。
- 流中途停顿：watchdog 主动关闭上游响应流，不再阻塞在 `readLine()`。
- 客户端断连：SSE 心跳 + `PrintWriter.checkError()`，检测后停止上游。
- 真实增量流：`stream=true` 解析 SSE delta；`<final>` 标签跨分块状态机。
- Nacos：兼容 Go 的 `{"configs":[...]}`、legacy Map、domain 拆分 data id；新增敏感键默认脱敏。
- Milvus：建集合/写入/加载/bootstrap；图片 SQL 使用 `p.` 限定列名。
- Failsafe 接入：`-DskipITs=false` 一条命令跑全部集成测试。
- 并发幂等测试、误操作防护测试、取消/断连测试、跨分块解析测试。

## 5. 下一步

1. 启动 Nacos/MinIO/Milvus，跑真实持久化、文件往返、集合检索。
2. 配模型/语音测试密钥，跑真实 Agent/TTS/ASR。
3. 逐字迁移 Go 全部默认 Prompt、意图 Prompt、规划/记忆链路。
4. 跑 Web/Android/Node 评估联调。
5. 用真实历史库副本验证补列接管。
