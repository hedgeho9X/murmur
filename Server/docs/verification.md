# 本地验收记录

日期：2026-09-26。环境：macOS、Node 22、JDK 17、OrbStack Docker。

## 已验证

- TypeScript typecheck 通过。
- 9 项 API 集成测试通过：鉴权/校验；8 个并发相同 UUIDv7 请求只有一次 201、其余 409；失败事务回滚；数据库拒绝 UPDATE 消息；标题修改/游标分页/删除后同 ID 可重新创建；真实签名图片上传读取/防覆盖/级联清理重试；损坏图片与缺失上传拒绝；OpenAPI/Scalar 响应。
- PostgreSQL 测试使用临时独立数据库，测试后删除；S3 使用本次测试生成的 UUID 对象并清理。
- Kotlin 生成客户端编译通过。2 项手写契约测试验证多态 JSON 的编解码，以及真实 HTTP 创建、重复 ID 冲突、读取、删除、删除后查询 404。
- Kotlin 任意 JSON 映射为 JsonElement，包含嵌套对象、数组、数字、布尔值与 null 的工具参数/结果均通过往返测试。
- Scalar HTTP 200；文档契约为同一 Hono route 导出。未声称完成文档页面的视觉验收。
- 凭据不进入请求体或生成客户端；生成产物与 OpenAPI 一致性由 CI 验证。

## 实现时修正

- RustFS 文档中的 1.0.1 标签在 registry 不存在，改为实际拉取镜像的固定 digest。
- Drizzle 迁移外键指向 public schema，测试改用临时独立数据库，避免 search_path 隔离产生跨 schema 外键。
- Kotlin 生成器默认任意 JSON 为 Any，实际编译失败；通过受版本控制的 type/import mappings 修正，不手改生成代码。

## 未验证 / 未实现

- Android APK、真机、公网 HTTPS、远程连接。
- Hermes 执行、运行中消息流、服务崩溃时片段恢复、追加消息 API。
- 搜索与附件筛选。

当前 API 成功仅表示帖子/首条消息已保存，不表示 Agent 已执行。删除先完成数据库事务，S3 清理由分钟级 worker 重试，存在短暂延迟。

## UUIDv7 创建接口

新增迁移先删除回执清理触发器及函数，再删除 idempotency 表；旧迁移保留作为历史账本。当前创建接口不接收 Idempotency-Key。新建请求缺少 ID 或提供 UUIDv4 时返回 422。图片上传每次分配独立 ID，完成操作仍可安全重复执行。
