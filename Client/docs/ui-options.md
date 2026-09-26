# UI 参考方向

2026-09-26 查询官方页面，尚未采用新依赖。

| 方向 | 用途 | 取舍 |
| --- | --- | --- |
| [Miuix](https://github.com/compose-miuix-ui/miuix#screenshots) | 完整 Compose 组件、图标、平滑圆角及动效，适合系统化的小米风格 | 官方标为实验性；采用前需确认 Android 最低版本和各模块兼容性 |
| [Composables UI](https://composables.com/ui) | 查看已有组件视觉，挑选输入、列表、菜单等表现 | 不必整套引入；与记录流程按需组合 |
| [Compose Unstyled](https://composeunstyled.com/) | 无默认视觉的组件基础，管理交互与可访问性，自行定义外观 | 更贴近简约记录风格，但需要维护自己的排版、间距与组件规范 |
| [自定义 Compose 设计系统](https://developer.android.com/develop/ui/compose/designsystems/custom) | 使用 Foundation 和自定义 tokens，逐步替换 Material 外观 | 控制力最大，也需要更多交互和可访问性验证 |

Murmur 采用 Composables UI 的视觉方向：中性色、轻边界、正文优先、小间距。原生控件仍基于 Compose Foundation / Material3；尚未接入 `com.composables:ui` 依赖。其 0.2.0 Maven 依赖为 Kotlin 2.4.0、Compose 1.11.1，与本项目 Kotlin 2.2.20 / Compose 1.9 工具链不同。

## 录音动效参考

- [SiriWave 在线演示](https://kopiro.github.io/siriwave/) / [源码](https://github.com/kopiro/siriwave)：连续曲线、振幅和平滑相位；Murmur 使用原生 Canvas 独立实现，不嵌入网页。
- [Compose AudioWaveform](https://github.com/lincollincol/compose-audiowaveform)：音频采样柱状图与拖动播放进度，适合后续音频回放，本次录音不引入。

实时曲线始终存在，真实 PCM RMS 经对数映射后驱动振幅，不再由字幕静音门控隐藏；无声回落到细线。字幕仍按可用宽度裁成一行末尾，正文完整保存。调试包每 20 帧记录采集计数及最大 RMS，不打印音频、转写文本或令牌。

## 编辑与标签

编辑工具仅提供标签、图片、加粗、列表。正文保存 Markdown，详情与列表复用 multiplatform-markdown-renderer 0.37.0。标签由用户正文中的 `#标签` 自动派生，客户端不单独传递或编辑标签。草稿改文字即改变提交后的标签；创建和补充时服务端提取标签写入 Post 搜索索引，已发布用户文字支持直接覆盖，不保留历史；图片、助理和工具记录保持不变。工具栏 `#` 只在光标处插入字符。顶部 `#前缀` 查询已有标签，选中补全项后精确筛选。

参考截图位于本地 `ref/`，包含个人内容，不推送到开源仓库。

记录默认不触发 AI 回复。未来详情页可增加用户主动触发的“问 AI”入口，本版不显示占位按钮。

## 本地验收

- 后端 15 项测试通过，覆盖正文标签提取、改文字后标签重算、同帖其他消息仍保留标签、用户文字覆盖而不增加消息、拒绝修改助理消息、编辑时保留图片。
- 生成 Kotlin 客户端真实 HTTP 契约测试通过，覆盖创建、标签补全、筛选和正文编辑。
- Android 模拟器确认：正文 `#work` 自动建标签，编辑为 `#life` 后旧标签消失，搜索 `#li` 返回 `#life`；照片长按缩小、无外框、底部垃圾桶进入变色、释放删除；静音录音细线持续可见。
- 模拟器麦克风采集 RMS 为零；真人音量、识别和三段震动手感仍需手机体验确认，不能用静音截图代替验证。
