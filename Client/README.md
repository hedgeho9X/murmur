# Murmur Android

Kotlin 原生 Android 客户端，Compose UI；最低 Android 8（API 26）。

## 目录

- `app/`：取景、CameraX 拍照、AudioRecord 录音、ASR WebSocket、可恢复草稿和帖子浏览。
- `api/`：由 OpenAPI Generator 生成的 Kotlin 接口与 DTO；手写 Gradle 配置同时支持独立测试及 App 子模块引用。
- `prototype/`：用于交互讨论的 HTML 原型，仍使用模拟语音，与真实 Android 应用分开。
- `docs/asr.md`：选型及流式显示说明。

## 构建

需要 JDK 17、Android SDK 36。通过 Android Studio 打开本目录，或配置 `local.properties` 的 sdk.dir（不要提交个人路径）。

```sh
sh gradlew :app:assembleDebug # 构建开发 APK
sh gradlew :app:testDebugUnitTest :app:lintDebug # 验证合并逻辑、UUID 和静态问题
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。调试版允许 HTTP；正式构建要求 HTTPS。ASR 供应商凭据仅配置在 Server/.env，不写入客户端。

## 连接开发服务

先按 Server/README.md 启动服务、存储并填写 ASR_API_KEY。App 右上角连接设置填写后端地址和 API_TOKEN。令牌通过 Android Keystore AES-GCM 加密保存，草稿及附件存于应用私有目录，禁止系统备份。

USB 真机或本机模拟器可以反向转发端口：

```sh
adb reverse tcp:8787 tcp:8787 # 转发 Hono
adb reverse tcp:59000 tcp:59000 # 转发签名 URL 使用的本地 S3
```

此时 App 地址用 `http://127.0.0.1:8787/`。局域网真机需要 Server 的 HOST/STORAGE_BIND 和 S3_PUBLIC_ENDPOINT 都配置为手机可达地址；不能修改已经签名的 URL 主机名。

## 已实现交互

- 首页圆角方形取景；单击拍照、长按跳过拍照直接录音。相机权限未授予时点击相机入口授权。
- 拍照后左侧录音、右侧铅笔；录音态无框、波形居中、下方只显示单行尾部转写，静音留白。
- partial 按 segment_id 替换，final 定稿；完整正文持续保存。停止后等待 completed，再开放编辑。
- 键盘和语音共用正文，底部编辑器支持相册多图；上传失败保留草稿及已上传资源 ID。
- 帖子列表、正文预览、关键词搜索、照片筛选、详情、追加记录与级联删除。
- 新帖子和追加消息均在草稿中生成一次 UUIDv7，重试沿用；消息不可编辑。

## 范围

本版完成记录与 ASR 链路，尚未连接 Hermes 生成回复。工具消息展示兼容已定义的数据库契约，但客户端不伪造助手响应。手机进入后台会停止采集并等待已有音频收尾，不提供后台录音。

生成接口：在 Server/ 运行 `npm run codegen`。`api/src/main` 不手工修改；供应商 WS 协议由 `SpeechRecorder.kt` 单独实现。

验收范围见 [docs/acceptance.md](docs/acceptance.md)。REST API、ASR 事件 DTO 和路径均由 Server OpenAPI 生成，手写层仅组织业务调用、设备采集及 WebSocket 生命周期。

构建局域网体验包时可传 Gradle 参数 `-PmurmurApiUrl=http://你的局域网IP:8787/`，它只设置首次使用的默认地址；设置中已保存的地址优先，API 令牌仍需自行填写，不嵌入 APK。

## 一键真机开发

手机开启开发者选项和 USB 调试，连接 Mac 后在手机上允许调试授权。后端先启动，然后从仓库根目录运行：

```sh
python3 Client/scripts/dev-install.py --serial 设备序列号 # 增量构建、覆盖安装、自动配置并启动
python3 Client/scripts/dev-install.py --serial 设备序列号 --skip-build # 复用现有 APK
```

单一设备时可省略 --serial。脚本使用 install -r 保留草稿和设置，不卸载应用；从 Server/.env 读取 API_TOKEN，经 stdin 写入应用私有文件。Debug App 启动后通过 Keystore 加密保存并删除临时文件，Release 不读取该文件。令牌不进入源码、APK、命令参数或日志。

默认通过 adb reverse 使用 USB 连接 API；S3 签名地址仍由后端 S3_PUBLIC_ENDPOINT 决定。当前局域网配置可以继续使用，纯 USB 模式则设为 localhost:59000 并重启后端。也可传 --url 指定可达地址。

无线调试需要 Android 11+：在手机无线调试中选择配对码配对，电脑执行 adb pair 后再 adb connect；两者端口可能不同。配对后复用同一安装脚本。首次授权/配对仍需在手机确认。

日常手机体验默认安装启用 R8 的 `performance` 包；脚本先用同签名 debug 包完成一次性私有配置，再覆盖优化包，保留草稿和令牌。需要调试器时传 `--debug`。`--skip-build` 默认要求两种 APK 都已构建。性能对照见 [scroll-performance.md](docs/scroll-performance.md)。
