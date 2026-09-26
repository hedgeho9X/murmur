# 帖子与不可变消息

本切片实现 Hono 帖子 CRUD、图片上传及 Kotlin 客户端契约；不调用 Hermes。

## 数据边界

- Post 是持续会话的容器，只有标题可以修改。
- Android 在创建草稿时生成 UUIDv7，并在本地保存为 post.id；编辑与网络重试不换 ID。
- 创建接口必须接收客户端 UUIDv7，不生成帖子 ID。数据库主键防止重复，已存在时统一返回 409 POST_ALREADY_EXISTS，不比较内容或返回首次响应。
- 手机因断网无法确认创建结果时，可用同一 ID 重试；收到 409 后 GET 该帖子。
- 物理删除后不保留墓碑，同一 ID 可以重新创建。客户端应取消已删除草稿的待发送请求。
- Message 入库后不可 UPDATE；数据库触发器强制这一点。删除所属帖子时级联 DELETE。
- role 为 user / assistant / tool。工具调用位于 assistant.content.parts，工具结果是独立 tool 消息。
- content 是 JSONB 对象，包含有序 parts。is_error 在工具 content 中；finish_reason 在 assistant content 中。
- turn_id 是一次发送及其整段回应；不是排序号，也不是模型请求 ID。
- 不使用 sequence、input_source、version、messages.updated_at、独立 tool_calls 或幂等回执表。
- 数据库时间精度为毫秒。消息与帖子仍按 created_at、id 稳定排序；UUIDv7 的时间顺序不替代服务端创建时间。
- 图片按 parts 数组顺序展示；images.message_id 表达单一归属。

## 创建链路

Android（已保存的 UUIDv7）→ Hono/Zod 校验 → PostgreSQL 事务插入 Post → 插入 user Message → 绑定 ready 图片 → 提交 → 201。

帖子主键冲突时不插入消息，返回 409。图片绑定失败时帖子、消息和此前附件绑定全部回滚，可以使用同一 ID 修正请求后重试。

## 图片链路

申请 upload → Android PUT 临时 S3 key → complete 检查大小并实际解码 → 将已验证字节写入最终 key → ready → 发帖关联。

上传申请不需要幂等键，每次生成独立图片 ID；重复申请可能留下未绑定图片，一天后回收。已取得 ID 和签名地址时可重试同一临时 PUT，complete 仍可重复调用。
支持单帧 JPEG/PNG/WebP，最多 10 MiB、2500 万像素。bucket 非公开；GET 签名 5 分钟，PUT 签名 15 分钟。
complete 持有图片行锁直到存储验证完成。若 S3 成功、数据库事务失败，可重试完成；无人重试的记录会过期清理。

删除 Post → 外键删除 Message/Image → 同事务触发器写 object_cleanup → API 每分钟重试 S3 删除。临时对象生命周期策略处理旧上传链接被再次使用的遗留对象。

## 契约与目录

Hono Zod route → OpenAPI 3.0.3 → Scalar + 固定版本 OpenAPI Generator → Kotlin Retrofit/coroutines/kotlinx.serialization。
任意 JSON 映射为 JsonElement；生成代码不手改。认证、Android 草稿持久化与重试决策由调用方负责。

- src/api/：命名 createRoute 定义与路由注册，注册上方标明方法和路径。
- src/modules/posts/：帖子契约、服务、仓储。
- src/modules/images/：图片契约、服务、仓储、S3 适配与清理。
- src/modules/messages/：消息内容契约。
- src/database/schemas/：posts、messages、images 模块表；图片清理队列与图片表同文件。外键直接引用目标 schema，index.ts 统一导出。
- src/database/database.client.ts：数据库连接。
- src/common/：共享契约、异常和序列化。

调用链 api → service → repository → PostgreSQL；图片 service 另调用 S3 适配器。创建仓储将同一事务传给附件回调，避免跨模块部分提交。

## 当前边界

尚未接入 Hermes、流式执行、服务崩溃时片段恢复、追加消息 API、搜索筛选和 Android UI。当前 HTTP 只接受首条 user 消息，不能由客户端伪造 assistant/tool。
消息生成中先实时展示，结束或中断后 INSERT 的运行时逻辑属于后续切片。
