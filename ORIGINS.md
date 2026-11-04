# 源码与素材来源

## 实现来源

核心交易规则、页面解析、SQLite 表结构、导入规划、选择学习和 Compose 界面由 Kotlin 实现。开发过程参考过既有记账应用的操作方式、分类词汇和使用者提供的 Excel 报表结构；这些行为观察用于定义交互和数据含义。

| 范围 | 来源与实现方式 |
| --- | --- |
| 金额、交易、重复依据、选择策略 | 本项目 Kotlin 规则与合成测试 |
| 微信、支付宝解析器 | 按页面字段和交易状态建立的小型解析器，输入为独立节点快照 |
| 旧 Excel 导入 | 按报表列和金额语义实现 ZIP/XML 读取、预览、映射与事务提交 |
| 数据库与交换格式 | 本项目 SQLite 事务、迁移、引用检查和 ZIP 编解码 |
| Android 界面与系统适配 | Compose、Material 3、官方权限与通知 API 的项目实现 |
| 分类目录 | 日常收支词汇及行为观察整理，持久来源键和管理逻辑由项目实现 |
| 品牌图形 | 本项目双栏账页矢量与标准库生成器，GPL-3.0-only |
| 分类图标 | Lucide 子集，固定提交和逐项哈希见 design/icons/lucide/manifest.json |
| 测试样本 | 项目生成的合成账目、交易详情与报表 |
| Gradle Wrapper | Gradle 9.1.0，随附 LICENSE 与 NOTICE |

## 第三方内容

Lucide 原始 SVG 和完整 ISC / Feather MIT 通知保存在 design/icons/lucide/。Kotlin、AndroidX 与 Coroutines 通过构建依赖使用，完整许可见 legal/DEPENDENCIES.md 和应用许可页。

来源核查覆盖文件清单、图标哈希、许可全文和 APK 内容。当前开发方式为行为观察后的独立实现。公开分发范围为本仓库源码、合成测试、构建文件和来源可核对的第三方内容。
