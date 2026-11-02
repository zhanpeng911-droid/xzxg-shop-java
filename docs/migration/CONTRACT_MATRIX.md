# 接口契约矩阵

- 来源：`docs/MIGRATION_API_BASELINE.csv` 与 `backend/src/httpapi/server.go:48-138`
- 源码基线：`cabd0a1b01abac05f666c522d238c6c1ce092815`
- 说明：87 条是**路由注册**，不是 API 数量。前缀注册必须展开 handler 内动作。
- 状态：`已实现` / `仅骨架` / `未实现` / `待展开`。

## 1. 分组总览

| 模块 | 注册数 | 直接路径 | 需展开的前缀/动作 | Java 实现 | 契约测试 |
|---|---:|---|---|---|---|
| 健康检查 | 1 | `/api/v1/health` | — | **已实现** | **已覆盖** |
| 认证 | 4 | login/register/logout/me | — | 未实现 | 未实现 |
| 账号 | 4 | profile、contact、DELETE account | — | 未实现 | 未实现 |
| 上传与静态资源 | 5 | uploads/avatar、files | `uploads/avatar/`、`assets/ecommerce_agent_dataset/`、`files/` | 未实现 | 未实现 |
| 商品目录 | 4 | categories/tree、merchants、products | `products/`（详情/SKU/评价） | 未实现 | 未实现 |
| 活动与券 | 4 | promotions、coupons/available、coupons/mine | `coupons/`（领取） | 未实现 | 未实现 |
| 图片搜索 | 1 | `search/image` | — | 未实现 | 未实现 |
| 商家后台 | 13 | documents、orders、reviews、products、promotions、ingestions | `orders/`、`reviews/`、`products/`、`promotions/` | 未实现 | 未实现 |
| 管理后台 | 25 | accounts、configs、prompts、vector、evals、agent runs、documents、ingestions、orders、promotions、reviews、merchants、products | 带 `/` 的 PATCH/POST/GET 子动作 | 未实现 | 未实现 |
| 测评 | 4 | eval/intent、rag、products、image-search | — | 未实现 | 未实现 |
| 购物车 | 5 | cart、discount-preview、items | `cart/items/`（PATCH/DELETE） | 未实现 | 未实现 |
| 订单 | 4 | orders、`orders:checkout` | `orders/` GET/POST（支付/取消/收货/评价） | 未实现 | 未实现 |
| 语音 | 3 | speech/realtime、tts/config、tts | — | 未实现 | 未实现 |
| Agent | 10 | guide-suggestions、sessions、sessions/search、runs | `sessions/`、`:pin`、`:summarize`、`messages:stream`、`runs/`、`:cancel` | 未实现 | 未实现 |
| **合计** | **87** | | | | |

## 2. 已从源码确认的横切契约

| 契约 | 源码 | Java 状态 |
|---|---|---|
| CORS 默认 `*`，OPTIONS 空 204 | `server.go:2510` | 已实现/已测 |
| 限流 120 次/分钟固定窗口 | `middleware.go:94` | 已实现/已测 |
| 请求体 12 MiB | `middleware.go:114` | 已实现/已测 |
| 请求 ID 24 位 hex | `middleware.go:227` | 已实现/已测 |
| 错误对象 `code/message/request_id` | `server.go:2942` | 已实现/已测 |
| 角色前缀策略 | `middleware.go:178` | 待 P2 接入 |
| bcrypt + 历史 SHA-256 | `server.go:2615` | 待 P2 |
| 不透明 Bearer token | `server.go:2559`、`auth_tokens` | 待 P2 |

角色策略摘要（`routePolicy`）：

- 公开：`/api/v1/health`、`auth/login`、`auth/register`；GET `categories/tree`、
  `merchants`、`products`、`products/*`、`uploads/avatar/*`。
- 三角色：`auth/me`、`auth/logout`、`account*`、`POST uploads/avatar`。
- 仅用户：`cart*`、`agent*`、`orders*`、`coupons*`、`speech*`。
- 三角色：`files*`、`search/image`。
- 仅商家：`merchant*`。
- 仅管理员：`admin*`。
- 其余路径公开（由 mux 决定 404）。

## 3. 必须优先展开的前缀注册

| 注册 | handler | 需要确认 |
|---|---|---|
| `GET /api/v1/products/` | `handleProductAction` | 详情、SKU、评价分支 |
| `POST /api/v1/coupons/` | `handleCouponAction` | 领取动作后缀 |
| `GET /api/v1/files/` | `handleGetFile` | 文件 ID、鉴权、MIME |
| `PATCH/DELETE /api/v1/cart/items/` | `handleCartItemAction` | 数量/勾选/删除语义 |
| `GET/POST /api/v1/orders/` | `handleUserOrderAction` | 支付、取消、收货、评价 |
| `GET/PATCH/POST/DELETE /api/v1/agent/sessions/` | `handleAgentSessionAction` | 详情、置顶、摘要、删除、`messages:stream` |
| `GET/POST /api/v1/agent/runs/` | `handleAgentRunTrace` / `handleAgentRunAction` | trace、`:cancel` |
| `PATCH /api/v1/merchant/orders/` | `handleUpdateMerchantOrder` | 发货等动作 |
| `POST /api/v1/merchant/reviews/` | `handleMerchantReviewAction` | 回复动作 |
| `PATCH/DELETE /api/v1/merchant/products/` | 对应 handler | 归属与状态 |
| `PATCH /api/v1/merchant/promotions/` | `handleUpdateMerchantPromotion` | 更新动作 |
| `PATCH/POST /api/v1/admin/prompts/` | `handleAdminPromptAction` | 草稿、发布、历史 |
| `PATCH /api/v1/admin/configs/` | `handleUpdateAdminConfig` | key 编码与刷新 |
| `GET /api/v1/admin/evals/reports/` | `handleAdminEvalReportDetail` | 报告 ID/路径安全 |
| `GET /api/v1/admin/agent/runs/` | `handleAdminAgentRunTrace` | run ID、trace |
| `PATCH /api/v1/admin/{accounts,orders,promotions,reviews,products,merchants}/` | 对应 handler | 管理动作与权限 |
| `GET /api/v1/uploads/avatar/` | `http.FileServer` | 静态根目录与 404 |
| `GET /api/v1/assets/ecommerce_agent_dataset/` | `http.FileServer` | 数据集根目录 |

## 4. 数据契约

- 23 张表已在 `backend-java/src/main/resources/db/migration/V1__baseline_schema.sql`。
- 列/索引来自 `001_mysql_schema.sql`、`ensureAccessSchema`、`ensureCommerceV3Tables`。
- Demo 行拆到 `db/seed/local-demo.sql`，默认不执行。
- 旧库接管步骤见 `DB_TAKEOVER.md`。

## 5. 当前缺口

- 每个前缀路由的请求字段、状态码、错误 code、中文 message 尚未逐条落表。
- Android/Web 调用点尚未映射到 Java 实现。
- P2–P6 业务接口全部未实现，本矩阵不能作为完成验收。
