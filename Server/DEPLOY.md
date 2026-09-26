# Dokploy 部署

使用 GitHub provider 绑定 `hedgeho9X/murmur` 的 `main`，Compose Path 为 `./Server/compose.dokploy.yaml`。启用 push auto deploy，watch paths 为 `Server/**` 与 `.dockerignore`。自动部署直接响应 GitHub push；现有 CI 独立运行，不把 auto deploy 描述为等待 CI 通过。

Compose 内 API、PostgreSQL、RustFS 分别运行。数据库和图片使用持久卷，只有 API 与 S3 接入 Dokploy 代理网络，不绑定数据库端口或 S3 控制台。不要在更新中删除卷或打开 isolated deployment volumes，也不要额外配置 file mounts 改变 Compose 的工作目录。

环境变量通过 Dokploy 注入，凭据不进入镜像：`POSTGRES_PASSWORD`、`API_TOKEN`、`S3_ACCESS_KEY`、`S3_SECRET_KEY`、`S3_BUCKET`、`S3_PUBLIC_ENDPOINT`、`ASR_API_KEY`。内部 S3 地址为 Compose 私有网络别名；公开 S3 地址必须与签名 URL 使用的 HTTPS 主机完全一致。

首次启动及新镜像部署时，initialize 服务先迁移数据库，再等待 RustFS 的 `/health`，最后初始化私有 bucket 与临时文件生命周期。初始化失败则 API 不启动。现有本地笔记不会自动迁入新部署。

面板域名绑定：API 对应 `api:8787`，S3 对应 `storage:9000`，均使用 HTTPS。DNS 需先指向部署服务器，再检查证书签发、`/health`、鉴权请求、S3 签名上传/读取和 ASR WebSocket；仅显示 deployed 不能代替外部验收。

镜像从仓库根目录构建：Dockerfile 为 `Server/Dockerfile`。运行镜像只含编译后的 JavaScript、生产依赖及 SQL 迁移，不携带 `.env`、Android 文件或私人参考图片。
