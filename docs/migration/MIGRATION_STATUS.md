# 迁移状态

- 更新时间：2026-11-02（Asia/Hong_Kong）
- 源码：`D:\claudecode\shop`，`HEAD = cabd0a1b01abac05f666c522d238c6c1ce092815`
- 当前阶段：P2–P6 主体实现完成；P7 未完成客户端联调，外部服务联调未完成

## 1. 已完成并有实测结果

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | Spring Boot 工程、Maven Wrapper、Flyway V1（23 表）、旧库 baseline | 完成 |
| P2 | bcrypt/历史 SHA-256、token、账号资料、类目/商家/商品/SKU、商家与管理员商品操作 | 完成 |
| P3 | 购物车、优惠预览、跨商家拆单、mock 支付、发货/收货、评价/回复、券、活动 | 完成 |
| P4 | MinIO 适配、文件元数据、知识入库/分块/去重、关键词+向量检索、64 维图片直方图、图片搜索 | 完成（外部服务未联调） |
| P5 | 会话 CRUD/搜索/置顶/摘要、POST SSE、重复消息协议、20 工具 + 6 skill、记忆、风控、trace、Prompt 管理 | 完成（LLM 未联调） |
| P6 | 向导建议、TTS/实时 ASR 适配、测评接口/后台、商品导入命令 | 完成（供应商未联调） |

### 实测命令与结果

```text
backend-java> mvnw clean verify
Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

# 隔离 MySQL（xzxg_shop_all_it），同一库顺序执行 P2–P6
mvnw -Dtest=P2FlowIT,P3FlowIT,P4FlowIT,P5FlowIT,P6FlowIT test
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖的真实流程：

- P2：历史 SHA-256 登录后升级 bcrypt；token 生成/注销；角色 401/403；
  商品 camelCase 契约；商家商品创建/修改/删除；管理员账号列表与状态校验。
- P3：加购 → 优惠预览（平台 30 + 商家 80）→ 跨商家拆单 → 支付 → 发货 →
  收货 → 评价 → 商家回复 → 管理员隐藏；券领取与每人限领；空购物车结算 400。
- P4：商家资料创建/同 Hash 去重；HTML 非结构化入库/重复标记；关键词召回；
  图片搜索在向量关闭时返回 `no_match/image_vector_index_unavailable`；
  64 维直方图（含透明像素）单测。
- P5：SSE 帧序（message_start/status/block_delta/text_delta/followups/message_end）；
  重复 client_message_id 返回三事件协议且同 run_id；购物车确定性路径；
  风控拦截；历史 blocks/segments 持久化；trace；置顶/摘要/搜索/删除；
  商家访问用户 Agent 被 403。
- P6：向导建议规则；意图/rag/商品测评；管理员测评看板；配置列表/更新；
  TTS 未配置时 501；导入命令。

### 商品导入

```text
java -jar target/shop-backend-0.1.0-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --shop.cli.import-products=true
```

实测导入 `quality/data/ecommerce_agent_dataset`：

```text
products=294, skus=973, knowledge_documents=1121, knowledge_chunks=1962, categories=83
```

### 数据库验证

- 空库：Flyway V1 创建 23 张表 + `flyway_schema_history`；local profile demo seed 生效。
- 旧库：复制 23 表+数据（无 Flyway 历史）→ 显式 baseline=1 → 应用启动；
  商品/账号行数保持不变，`flyway_schema_history` 仅 1 条 baseline 记录。
- 旧库“缺列”场景尚未用真实历史库副本验证。

## 2. 未完成 / 未验证

| 项 | 原因 |
|---|---|
| MinIO 上传/下载联调 | 本机 MinIO 未启动；代码已实现，未做真实 bucket 往返 |
| Milvus 向量索引 bootstrap/upsert | 只实现查询与状态；未实现集合创建/向量写入，未连接 Milvus |
| 真实模型 Agent 路径 | 无 API Key；已测本地确定性兜底；LLM ReAct 未联调 |
| LLM 流式 | 当前为完成后分块发送 SSE，不是 token 级实时流 |
| 语音供应商 | 无讯飞/豆包密钥；签名、TTS、ASR 代理已实现但未实测 |
| Nacos | 未实现；配置为进程内默认值，更新不驱动已创建 Bean |
| Prompt 内容 | 仅种子化 7 个简化默认 Prompt，未逐字迁移 Go 常量 |
| 前端/Android 联调 | 未运行 Web build、Android 构建或真实客户端 |
| 测评报告详情 | 无报告文件；看板扫描逻辑已实现 |
| 旧库缺列升级 | 未构造真实历史 schema 副本 |

## 3. 明确未做

- 没有修改前端业务代码，也没有运行 `npm run build`。
- 没有启动 Android 客户端或执行真实设备联调。
- 没有连接公网演示环境、生产数据库或真实支付/语音/模型服务。
- 没有 commit/push（收尾步骤单独执行）。

## 4. 下一步（如继续）

1. 启动 MinIO/Milvus，补齐文件往返与向量 bootstrap/upsert 验证。
2. 配置模型/语音测试密钥，跑真实 Agent、TTS、ASR。
3. 实现 Nacos 客户端并让动态配置驱动 CORS/限流/检索参数。
4. 用真实历史库副本验证 `DB_TAKEOVER.md` 的补列路径。
5. 运行 Web/Android 联调与 Node 测评脚本。
