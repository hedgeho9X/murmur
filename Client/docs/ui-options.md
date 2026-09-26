# UI 参考方向

2026-09-26 查询官方页面，尚未采用新依赖。

| 方向 | 用途 | 取舍 |
| --- | --- | --- |
| [Miuix](https://github.com/compose-miuix-ui/miuix#screenshots) | 完整 Compose 组件、图标、平滑圆角及动效，适合系统化的小米风格 | 官方标为实验性；采用前需确认 Android 最低版本和各模块兼容性 |
| [Composables UI](https://composables.com/ui) | 查看已有组件视觉，挑选输入、列表、菜单等表现 | 不必整套引入；与记录流程按需组合 |
| [Compose Unstyled](https://composeunstyled.com/) | 无默认视觉的组件基础，管理交互与可访问性，自行定义外观 | 更贴近简约记录风格，但需要维护自己的排版、间距与组件规范 |
| [自定义 Compose 设计系统](https://developer.android.com/develop/ui/compose/designsystems/custom) | 使用 Foundation 和自定义 tokens，逐步替换 Material 外观 | 控制力最大，也需要更多交互和可访问性验证 |

Murmur 暂保持白底、轻量图标和正文优先的布局。用户选择前不整体替换组件库；先修复照片操作、编辑器键盘协同与转写可见性。
