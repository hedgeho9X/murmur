# 真机滑动对照

2026-09-26，在 Xiaomi 23127PN0CC 上，对同一笔记列表执行三组上下滑动，每次滑动 350ms。每轮开始前重置 gfxinfo；优化包在进入列表并预热后采样。只保存聚合统计，不保存用户正文。

| 指标 | Debug | R8 优化包 | 优化包复测 |
| --- | ---: | ---: | ---: |
| 帧数 | 522 | 488 | 564 |
| P95 | 12ms | 6ms | 7ms |
| P99 | 19ms | 11ms | 12ms |
| 系统掉帧比例 | 0.19% | 0.20% | 0.18% |
| Legacy 掉帧比例 | 14.94% | 0.82% | 1.42% |

采样帧间隔约 8.31ms。新旧掉帧口径定义不同，不能混用；本轮主要证据是帧耗时下降，不代表零掉帧，也不是长期 Macrobenchmark 结果。原始聚合值见 `benchmarks/scroll-2026-09-26.json`。

`performance` 构建关闭调试并启用 R8 与资源裁剪，使用本地 debug 签名保持覆盖安装与草稿兼容；只用于本地体验，不是生产发布签名。局域网 HTTP 仅在 debug/performance manifest 中放开，主 manifest 不变。

`dev-install.py` 默认先用调试包导入私有连接配置，再覆盖同签名优化包；`--debug` 显式保留调试包。不能用空列表或请求失败时的零帧统计作为性能结果。

方法参考：[Android 官方 Compose 性能指南](https://developer.android.com/develop/ui/compose/performance)，建议在开启 R8 的非调试构建上评估实际性能。
