# 已知差异与待确认项

本文件区分：已按源码对齐、仍存在的有意差异、实现但未联调、原系统既有缺口。

## 1. 已按 Go 源码对齐

| 项目 | Java 实现 | 源码依据 |
|---|---|---|
| 健康检查 | `{"status":"ok"}` | `server.go:140` |
| CORS | 默认 `*`；响应带 Allow-Headers/Methods；OPTIONS 空 204 | `server.go:2510-2546` |
| 限流 | 固定窗口 120 次/60 秒；跳过 OPTIONS、health、`/api/v1/eval/` | `middleware.go:26-112` |
| 限流 key | 登录后 `role:account_id`，否则客户端 IP | `middleware.go:101-105` |
| 请求体 | 12 MiB，非 GET/HEAD | `middleware.go:114-122` |
| 请求 ID | 24 位小写 hex（12 随机字节） | `middleware.go:227-233` |
| 未登录/越权 | `unauthorized/请先登录`、`forbidden/当前账号无权访问` | `middleware.go:142-148` |
| JSON 解析失败 | `bad_request/请求 JSON 不合法` | `server.go:150,183` |
| 可信代理 | 只有可信 peer 才读 XFF，取第一跳；无 X-Real-IP | `middleware.go:235-291` |
| 登录密码 | bcrypt `$2a/$2b/$2y`；历史 SHA-256 校验后升级 | `server.go:2607-2626` |
| Token | `tok_` + 32 字节 base64url；存 SHA-256；24h；兼容明文旧 token | `mysql.go:637-650` |
| 商品 JSON | camelCase 卡字段；价格字符串；`productId/stockStatus` | `domain/types.go:277-326` |
| 订单 JSON | snake_case；金额字符串；时间字段零值也输出 | `domain/types.go:397-441` |
| 幂等 | 消息 `(account_id, session_id, client_message_id)`、run `(account_id,message_id)` 唯一键 | `mysql.go:209,211` |
| 重复流 | `message_start → error(duplicate_message) → message_end`，同 run_id | `server.go:2326-2328` |
| SSE 帧 | `event: <type>\ndata: <JSON>\n\n`，JSON 内保留 `type` | `server.go:2426-2432` |
| 事务 | 结算锁购物车、条件扣库存、按商家拆单、写 mock 支付、清空已选 | `mysql.go:1966-2065` |
| 风控默认 | 违法/违禁/假货/绕过风控 → `unsafe_request`；`risk` 账号拦截 | `risk/checker.go` |

## 2. 仍然存在的有意差异

| 项目 | Java 行为 | Go 行为 | 影响 |
|---|---|---|---|
| 未匹配路由 | 404 JSON `not_found` | ServeMux 纯文本 | 客户端一般不调用未知路径 |
| 方法不允许 | 405 JSON | ServeMux 纯文本 + Allow | 同上 |
| 请求 ID 校验 | 拒绝 >256 字符或控制字符 | 任意非空值回传 | 防响应头注入 |
| 超限请求体 | `Content-Length` 预检 413 | `MaxBytesReader` 读取时中断 | chunked 由容器限制兜底 |
| 限流状态 | 超过 100k key 淘汰旧窗口 | map 无上限 | 防内存无限增长 |
| Demo seed | 仅显式开关 | 迁移时总是写 demo 行 | 生产保护 |
| 旧库回填 | 不自动执行，见 `DB_TAKEOVER.md` | 启动时自动补列/回填 | 避免启动即改历史数据 |
| 迁移工具 | Flyway + 显式 baseline | 幂等 SQL | 结构可审计 |
| 头像静态 404 | 空 404 | 纯文本 | 低风险 |
| Nacos | 进程内配置（`ConfigService`） | Nacos + 内存降级 | 动态刷新未实现 |

## 3. 已实现但未联调（不得当作已验证）

| 能力 | 实现情况 | 缺什么 |
|---|---|---|
| MinIO 文件上传/下载 | `ObjectStorage` + `/api/v1/files` + `stored_files` | 本机无 MinIO，未做 bucket 往返 |
| Milvus 检索 | REST search + 状态查询；知识/图片检索失败降级 | 未实现 collection 创建/向量 upsert/bootstrap |
| 真实模型 Agent | OpenAI 兼容 `chat/completions` + ReAct JSON 协议 | 无 API Key；只验证了确定性兜底 |
| LLM 流式 | 回答完成后按 24 字符分块发 SSE | 不是 token 级实时流 |
| Prompt | 数据库读写/草稿/发布 + 7 个简化默认值 | 未逐字迁移 Go 的全部 Prompt 常量与意图 Prompt |
| 记忆 | 最近 5 条对话 + 当前 session | 未迁移 Go 的 need_summary/memory_retrieval 模型流程 |
| 规划/意图 | 关键词规则 + 可选模型 ReAct | 未迁移 Go 的一级 route/P1-P6 模型规划 |
| 语音 TTS | 讯飞 WSS 签名 + 豆包 HTTP 适配 | 无密钥，未实测 |
| 实时 ASR | WebSocket 代理、控制帧、ready/partial/final | 无密钥，未实测 |
| 测评 | 意图/rag/商品/图片接口 + 看板/报告扫描 | 无真实报告文件与 Node 脚本联调 |
| 商品导入 | 294 商品/973 SKU/1121 文档/1962 chunk 实测 | 未与 Go 导入结果逐行对照 |

## 4. 原系统既有缺口（不扩大范围）

- 支付为 mock，不接真实支付/退款。
- 下单优惠金额固定为 0，优惠未实际抵扣。
- 取消/超时的库存返还、定时关单、退款售后未实现。
- 导入命令的硬编码历史分类 DELETE 未进入普通导入/启动。
- 原取消实现可能未终止模型流、断连时状态回写可能失败（未实测）。

## 5. 待确认

- 旧客户端是否依赖 404/405 的纯文本响应。
- `Content-Length` 缺失时的超限请求是否需要流式限制。
- 真实模型下 SSE 是否需要严格 token 级实时性。
- 语音供应商签名在真实密钥/时间窗口下的兼容性。
- Milvus collection 的字段名/维度是否与当前 REST 客户端一致。
