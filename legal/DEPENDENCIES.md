# 第三方依赖与许可

项目自写源码与品牌图形采用 GPL-3.0-only，完整文本为根目录 LICENSE。第三方组件维持各自许可，分发文本随源码和 APK 提供。

| 范围 | 固定版本或来源 | 许可 |
| --- | --- | --- |
| Kotlin / Compose 编译插件 | 2.2.10 | Apache-2.0 |
| AndroidX Compose BOM / Material 3 / Navigation Suite | 2025.08.01 / 1.4.0 / 1.4.0 | Apache-2.0 |
| AndroidX Material 3 Adaptive Layout | 1.1.0 | Apache-2.0 |
| AndroidX Activity / Lifecycle / SavedState | 1.10.1 / 2.9.3 / 1.3.1 | Apache-2.0 |
| Kotlin Coroutines | 1.10.2 | Apache-2.0 |
| Lucide 分类图标 | a630cec77eac6568b989e87e4947a8bfe45caacd，45 个 SVG | ISC / Feather MIT |
| Gradle Wrapper | 9.1.0 | Apache-2.0 / NOTICE |

APK 中的 app/src/main/assets/licenses 保存 GPL、Apache 和 Lucide 完整文本，可从关于页查看。Gradle Wrapper 的 LICENSE 和 NOTICE 位于 gradle/wrapper/。Lucide 逐项 URL、哈希和映射键位于 design/icons/lucide/manifest.json。

来源：[AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)、[Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt)、[Coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt)、[Lucide](https://github.com/lucide-icons/lucide/tree/a630cec77eac6568b989e87e4947a8bfe45caacd)。

应用构建所用版本由 Gradle 文件固定。发布流程核对解析依赖、许可资产及对应源码，见 docs/RELEASING.md。
