# 验证报告

- 日期：2026-11-02
- 环境：Windows 10.0.26200、Temurin 25.0.4.1（release 21）、
  Spring Boot 4.1.2、Maven 3.9.11（Wrapper 3.3.4）、MySQL 8.0.12（本机 phpstudy）
- 源码：`main@cabd0a1b01abac05f666c522d238c6c1ce092815`

## 1. 构建与单元测试

```text
cd backend-java
mvnw clean verify
Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖：请求 ID/CORS/限流/体积限制/错误对象、可信代理、固定窗口边界、
文档分块、HTML/JSON 入库解析、64 维直方图（透明像素/桶边界）。

## 2. MySQL 集成测试

隔离库 `xzxg_shop_all_it`，单次启动顺序执行：

```text
mvnw -Dtest=P2FlowIT,P3FlowIT,P4FlowIT,P5FlowIT,P6FlowIT test
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试 | 验证内容 |
|---|---|
| P2FlowIT | 历史 SHA-256 登录升级 bcrypt、token 注销、角色 401/403、商品 camelCase、商家商品 CRUD、管理员操作 |
| P3FlowIT | 加购、优惠预览、跨商家拆单、mock 支付、发货、收货、评价/回复、管理员隐藏、券限领、空车结算 |
| P4FlowIT | 资料入库/同 Hash 去重、HTML 入库重复标记、关键词召回、图片搜索降级 |
| P5FlowIT | SSE 帧序、重复消息三事件协议、购物车确定性路径、风控、历史 blocks/segments、trace、置顶/摘要/搜索、角色隔离 |
| P6FlowIT | 向导建议、意图/rag/商品测评、测评看板、配置读写、TTS 未配置 501 |

## 3. 数据库初始化与接管

| 场景 | 结果 |
|---|---|
| 空库 + Flyway V1 | 23 张业务表 + `flyway_schema_history`；demo seed 3 商品/3 账号 |
| 旧库副本（23 表+数据，无 Flyway 历史） | 显式 `baseline=1` 后应用启动；商品/账号行数不变；历史表 1 条 baseline |
| 旧库缺列场景 | 未构造真实历史副本，未验证 |

## 4. 商品导入

```text
java -jar ... --spring.main.web-application-type=none --shop.cli.import-products=true
products=294, skus=973, knowledge_documents=1121, knowledge_chunks=1962, categories=83
```

## 5. 未验证（外部依赖）

- MinIO 上传/下载：本机 MinIO 未启动。
- Milvus 检索：未连接；集合创建/向量写入未实现。
- 真实模型 Agent：无 API Key；仅验证本地兜底。
- 讯飞/豆包 TTS、实时 ASR：无密钥；签名与代理代码未实测。
- Nacos：未实现；配置为进程内。
- Web/Android 客户端：未构建、未联调。
- Node 测评脚本：未对 Java API 运行。
- 测评报告详情：无报告文件。

以上项目不得在交付说明中写成“已验证”。
