# Murmur Client

客户端使用 Kotlin，仅面向 Android。当前尚未创建 Android App/UI 模块。

- `api/`：由 OpenAPI Generator 生成的 Kotlin JVM 模块，使用 Retrofit、coroutines 和 kotlinx.serialization，后续可接入 Android。
- `api/src/test/kotlin/cn/hedgeho9/murmur/contract/`：手写 JSON/真实 HTTP 契约测试。
- `codegen.json`：Kotlin 客户端生成配置。生成的 `api/src/main` 不手工修改。

API 契约源位于 `../Server/openapi/openapi.json`。在 `Server/` 运行 `npm run codegen` 重新生成；生成器版本固定在 `Server/openapitools.json`。

在 `Server/` 启动本地 API 后运行 `npm run test:kotlin`，脚本会把后端测试凭据通过环境传给 Gradle，不把密钥写入客户端文件。

API 模块使用 `cn.hedgeho9.murmur.api` 包名，Gradle 项目名为 `murmur-api`。Android 的界面、状态管理、认证与重试策略由后续手写模块负责。

## HTML 交互原型

[prototype/](prototype/README.md) 提供记录流程的独立浏览器原型，包含拍照、长按进入模拟录音、草稿编辑与本地记录。它不连接 ASR 或业务后端，不替代后续 Kotlin App。
