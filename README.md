# Murmur

[![CI](https://github.com/hedgeho9X/murmur/actions/workflows/ci.yml/badge.svg)](https://github.com/hedgeho9X/murmur/actions/workflows/ci.yml)

面向 Android 的个人记录与思考助手。通过照片、文字和语音记录想法，连接用户自己的 Agent，保留持续的帖子与会话。

**当前处于早期开发阶段：已实现原生 Android 记录界面、实时 ASR 转发与后端；Hermes 自动回复尚未接入。**

## 项目结构

| 目录 | 内容 |
| --- | --- |
| [Server](Server/README.md) | Hono API、PostgreSQL、S3 图片、SQL 迁移、Scalar/OpenAPI 与后端测试 |
| [Client](Client/README.md) | Compose Android App、生成的 Retrofit API 模块和契约测试 |

## 已实现

- 帖子创建、分页读取、详情、改标题与级联删除。
- user / assistant / tool 统一消息模型，content JSONB，turn_id 分组；用户笔记文字可直接编辑，不保留版本，助理与工具消息不可变。
- 客户端 UUIDv7 标识帖子，首条消息与帖子事务创建；重复 ID 返回 409。
- S3 签名上传、真实图片校验、私有读取与可重试清理。
- OpenAPI → Kotlin 客户端生成，多态 JSON 与真实 HTTP 契约测试。

HTTP 接口支持创建帖子及追加用户消息；assistant/tool 是已定义的存储契约，不代表已接入 Agent 执行。

## 本地开发

需要 Node.js 22+、Docker；验证 Kotlin 模块还需要 JDK 17。

```sh
git clone https://github.com/hedgeho9X/murmur.git # 下载仓库
cd murmur/Server # 后端命令统一在 Server 目录执行
npm ci # 安装锁定依赖
cp .env.example .env # 创建本地配置，随后按后端说明设置随机凭据
```

继续按 [后端启动说明](Server/README.md) 启动 PostgreSQL、RustFS 和 Hono。运行后可打开 [Scalar 文档](http://127.0.0.1:8787/docs)。

默认仅本机可访问。真实 Android 设备访问需要配置可达的 API 和 S3 地址；不要把开发凭据用于公开部署。

## 验证与贡献

接口契约由 Hono/Zod 定义，修改后运行 `npm run codegen`，不要直接修改生成的 Kotlin 主代码。CI 检查后端集成、生成产物一致性和 Kotlin 真实 HTTP 调用。详细设计见 [设计说明](Server/docs/design.md)。

采用 [MIT License](LICENSE)。

Android 构建与连接说明见 [Client/README.md](Client/README.md)。每次 CI 构建会生成可下载的开发 APK artifact。
