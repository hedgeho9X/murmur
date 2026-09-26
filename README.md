# MyAPP API

Kotlin / Android 个人记录助手的 Hono 后端。当前提供帖子 CRUD、首条不可变消息、S3 图片及生成客户端。

## 本地启动

需要 Node.js 22+、Docker；Kotlin 验证另外需要 JDK 17。复制 `.env.example` 为 `.env`，设置随机 API_TOKEN、POSTGRES_PASSWORD、S3_SECRET_KEY，并使 DATABASE_URL 密码一致；不要提交 `.env`。

```sh
npm ci # 安装固定依赖
 docker compose up -d # 启动本项目 PostgreSQL 和 RustFS
npm run db:migrate # 应用已提交的 SQL 迁移
npm run storage:init # 创建私有 bucket 与临时对象过期策略
npm run dev # 启动 Hono，默认仅本机可访问
```

- Scalar: http://127.0.0.1:8787/docs
- OpenAPI: http://127.0.0.1:8787/openapi.json
- RustFS console: http://127.0.0.1:59001
- 所有 `/api/*` 请求需要 `.env` 中 API_TOKEN 对应的 Bearer 认证。文档不持久化令牌。
- PostgreSQL 端口 55432；S3 端口 59000。使用项目独立命名卷，不影响已有数据库。

## 接口

| 方法   | 路径                         | 行为                                                 |
| ------ | ---------------------------- | ---------------------------------------------------- |
| POST   | /api/v1/posts                | 原子创建帖子和第一条 user 消息；必带 Idempotency-Key |
| GET    | /api/v1/posts                | limit/cursor 分页                                    |
| GET    | /api/v1/posts/{id}           | 帖子及有序消息                                       |
| PATCH  | /api/v1/posts/{id}           | 仅修改 title，null 清空                              |
| DELETE | /api/v1/posts/{id}           | 级联删除，重复删除返回 204                           |
| POST   | /api/v1/images/uploads       | 幂等申请签名 PUT，必带 Idempotency-Key               |
| POST   | /api/v1/images/{id}/complete | 校验真实图片并完成上传，重复调用安全                 |
| GET    | /api/v1/images/{id}/url      | ready 图片的临时读取地址                             |

创建帖子请求示例（JSONC，去掉注释后发送）：

```jsonc
{
  // 创建帖子及第一条用户消息
  "title": "一个想法", // 可省略或传 null
  "content": {
    // 消息内容
    "parts": [
      // 顺序即展示顺序
      { "type": "text", "text": "先记录，再一起思考" }, // 支持纯文本发帖
    ], // 图片块使用 type=image、image_id=已完成上传的ID
  }, // 不传 role、turn_id 或客户端时间
} // 重试必须复用原 Idempotency-Key
```

## 验证与生成

```sh
npm run typecheck # TypeScript 静态检查
npm test # 创建临时测试数据库，使用真实 PostgreSQL/S3，结束后清理
npm run codegen # 导出固定 OpenAPI 并生成 Kotlin 客户端
npm run test:kotlin # 编译并验证 JSON 和真实 HTTP 契约，需要本地 API 已启动
npm run storage:cleanup # 手动执行一次可重试清理；API 也会每分钟执行
```

测试数据库使用随机 test_ 名称，测试账号需有 CREATEDB 权限；只能指向本地开发数据库。生成目录 `clients/kotlin` 可作为 JVM 模块接入 Android，传入自己的 baseUrl 和认证拦截器；不要依赖生成器默认的 localhost 地址。

## Android 真机连接

设置 HOST=0.0.0.0、STORAGE_BIND=0.0.0.0、S3_PUBLIC_ENDPOINT=http://Mac的局域网IP:59000，然后重新创建存储容器并重启 API。Android API 地址为 http://Mac的局域网IP:8787。签名后的 URL 不可再替换主机名。开发 HTTP 需要 Android debug 网络安全配置；正式部署使用 HTTPS。本轮没有真机验收。

设计和限制见 [docs/design.md](docs/design.md)。
