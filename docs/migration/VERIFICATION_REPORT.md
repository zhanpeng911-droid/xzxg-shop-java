# 验证报告

- 日期：2026-11-02
- 环境：Windows 10.0.26200、Temurin 25.0.4.1（release 21）、
  Spring Boot 4.1.2、Maven 3.9.11（Wrapper 3.3.4）、MySQL 8.0.12（本机）
- 源码：`main@cabd0a1b01abac05f666c522d238c6c1ce092815`

## 1. 验收命令

```bash
cd backend-java
mvnw clean verify

# 需要隔离 MySQL；Failsafe 会跑全部 *IT
SHOP_IT_DB_URL='jdbc:mysql://127.0.0.1:3306/xzxg_shop_it?useSSL=false&serverTimezone=Asia/Hong_Kong&allowPublicKeyRetrieval=true' \
SHOP_IT_DB_USERNAME=root \
SHOP_IT_DB_PASSWORD=... \
mvnw verify -DskipITs=false
```

## 2. 最近一次结果

```text
Surefire:   Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
Failsafe:   Tests run: 9,  Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

跳过项：`TakeoverBaselineIT`（未设置 `SHOP_TAKEOVER_URL`，按设计跳过）。

### 单元/基础设施测试（41）

- 请求 ID、CORS、限流、体积限制、错误对象、可信代理
- 固定窗口边界、CIDR、客户端 IP
- 文档分块、HTML/JSON 入库解析
- 64 维直方图（透明像素/桶边界）
- 工具/skill 意图策略、常见查询分类
- Nacos 配置映射与密钥脱敏
- Milvus REST 建集合/写入/加载/查询载荷

### 集成测试（9，1 跳过）

| 测试 | 验证内容 |
|---|---|
| P2FlowIT | 历史 SHA-256 升级、token、角色、商品契约、商家 CRUD、管理员操作 |
| P3FlowIT | 加购、优惠预览、跨商家拆单、支付、发货、收货、评价、券 |
| P4FlowIT | 入库/去重、关键词召回、图片搜索降级 |
| P5FlowIT | SSE 帧序、重复消息、历史 segments、trace、置顶/摘要 |
| P6FlowIT | 向导建议、测评/看板、配置读写、TTS 501 |
| AgentPolicyIT | 工具白名单、商品 ID 白名单、取消中止、真实增量流、跨分块 `<final>` |
| IdempotencyIT | 8 并发同 client_message_id 仅 1 消息/1 run；8 并发加购数量=8 |
| TakeoverBaselineIT | 未设置环境时跳过 |

## 3. 其他实测

| 场景 | 结果 |
|---|---|
| 商品导入 | products=294, skus=973, documents=1121, chunks=1962, categories=83 |
| 空库 Flyway V1 | 23 张业务表 + 历史表；demo seed 生效 |
| 最终 schema 副本 baseline | 数据行数不变；历史表 1 条 baseline |
| 旧库缺列升级 | 未验证 |

## 4. 未验证（外部依赖）

- Nacos 真实服务、MinIO bucket 往返、真实 Milvus 集合检索。
- 真实模型 API、讯飞/豆包 TTS/ASR。
- Web/Android 客户端、Node 测评脚本。
- 测评报告详情（无报告文件）。

以上不得写成“验证通过”。
