# 帖子与不可变消息

本切片实现 Hono 帖子 CRUD、图片上传及 Kotlin 客户端契约；不调用 Hermes。

## 已确认的数据边界

- Post 是持续会话的容器，只有标题可以修改。
- Message 入库后不可 UPDATE；数据库触发器强制这一点。删除所属帖子时可以级联 DELETE。
- `role` 为 user / assistant / tool。工具调用位于 assistant.content.parts，工具结果是独立 tool 消息。
- `content` 是 JSONB 对象，包含有序 parts。is_error 在工具 content 中；finish_reason 在 assistant content 中。
- `turn_id` 是一次发送及其整段回应；不是排序号，也不是某个模型请求 ID。
- 不使用 sequence、input_source、version、messages.updated_at 或独立 tool_calls 表。
- 数据库时间精度固定到毫秒，与 API/Android 时间解析一致；消息按 created_at、id 稳定排序。当前不支持消息分支或并发回应排序。
- 图片按 parts 数组顺序展示；images.message_id 表达单一归属，因此不需要重复维护 message_images 关联表。

## 创建链路

Android → Hono/Zod 验证 → 事务内幂等锁 → 创建 Post → 创建 user Message → 原子绑定 ready 图片 → 保存响应回执 → 提交 → 201。

事务任一步失败全部回滚。幂等键按操作隔离；请求内容规范化后哈希。相同键/内容返回首次创建快照（标题后来改名也不改变首次快照）；不同内容为 409。
删除后留下无正文的幂等墓碑，返回 410，不会复活帖子。墓碑保留请求 SHA-256，不保留原文响应。

## 图片链路

申请 upload → Android PUT 临时 S3 key → complete 检查大小并实际解码 → 将已验证字节写入不可由上传链接修改的最终 key → ready → 发帖关联。

支持单帧 JPEG/PNG/WebP，最多 10 MiB、2500 万像素。bucket 非公开，读取返回 5 分钟签名 URL。上传链接 15 分钟；重试申请可刷新链接但沿用同一图片 ID。
complete 会持有图片行锁直到存储验证完成，这是单用户第一版的简化取舍。若存储成功、事务失败，重试仍能完成；无人重试的记录在一天后进入清理。

删除 Post → PostgreSQL 外键删除 Message/Image → 同事务触发器写 object_cleanup → API 每分钟重试 S3 删除。失败保留清理项。未绑定图片一天后过期；staging bucket lifecycle 是旧链接再上传的兜底。

## 尚未接入的能力

- Hermes 调用、assistant/tool 的运行时写入、执行结束与中断落库、流式事件持久化。
- 当前只开放首条 user 消息创建；后续追加消息需要单独接口与工具配对校验，不能让客户端伪造 assistant/tool。
- 搜索、附件筛选、完整 Android UI 和真机网络验收。
- 消息生成中先流式展示，单条结束或明确中断后 INSERT；服务崩溃前的流式片段恢复需要事件持久化，当前未实现。

## 契约与客户端

Hono Zod route → OpenAPI 3.0.3 → Scalar + 固定版本 OpenAPI Generator → Kotlin Retrofit/coroutines/kotlinx.serialization。
多态按 type/role discriminator 生成；任意 JSON 映射到 JsonElement，不能使用无法序列化的 Any。生成代码不手改。鉴权策略、重试决策和 Android 状态管理留给调用方。

## 目录与职责

采用按业务模块组织（feature-based modules）和点分隔职责命名（module.role.ts）。

- `src/api/app.api.ts`：HTTP 中间件、错误响应、模块路由与 Scalar 装配。
- `src/api/posts.api.ts`、`src/api/images.api.ts`：请求校验、调用 service、响应序列化。
- `src/modules/posts/`：posts.contracts.ts、posts.service.ts、posts.repository.ts。
- `src/modules/images/`：images.contracts.ts、images.service.ts、images.repository.ts、images.storage.ts、images.cleanup.ts。
- `src/modules/messages/messages.contracts.ts`：消息与内容块契约；尚无独立消息写入 API，不创建空 service/repository。
- `src/modules/idempotency/idempotency.repository.ts`：可跨模块使用的幂等事务与响应回执。
- `src/modules/modules.ts`：实例装配，向 HTTP 层注入服务。
- `src/database/`：数据库连接与 Drizzle 表定义；SQL 迁移继续在根 migrations/。
- `src/common/`：通用 ID/错误契约、异常和序列化帮助函数。

调用链：api → service → repository → PostgreSQL；图片 service 另调用 images.storage → S3。
service 负责业务校验、流程与跨模块协作；repository 负责 SQL、行锁和一致性读取。创建帖子时各 repository 共享同一个幂等事务，不能各自开启独立事务。
新增业务时在 modules/ 下建同名目录，在 api/ 下增加相应入口；只创建实际需要的职责文件。
