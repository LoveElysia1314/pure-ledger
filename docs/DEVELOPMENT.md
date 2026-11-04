# 开发环境

JDK 21、Android SDK Platform 36、Build Tools 36.0.0 和 Python 3.10 以上组成开发环境。Gradle 9.1.0 由 Wrapper 下载并校验，依赖版本由构建文件和锁文件记录。

## 配置

`JAVA_HOME` 指向 JDK，`ANDROID_HOME` 指向 Android SDK。Android Studio 可通过被 Git 忽略的 `local.properties` 配置 `sdk.dir`。

Windows PowerShell 工具可读取 `.private/developer.json`，支持 `javaHome`、`androidHome`、`gradleUserHome`、`robolectricDir`、`adbPath` 和 `serial`。该文件保存本机路径和设备标识。

```powershell
.\scripts\Project.ps1 Doctor
.\scripts\Project.ps1 Test
.\scripts\Project.ps1 TestAndroid
.\scripts\Project.ps1 Build
.\scripts\Project.ps1 Check
.\scripts\Project.ps1 Release
```

## Gradle

```powershell
.\gradlew.bat -PcoreOnly=true :core:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Linux 和 macOS 使用 `./gradlew`。默认工程加载 `app` 与 `core`；`-PcoreOnly=true` 适用于只检查纯逻辑的环境。

Robolectric 使用 API 32、34 和 36 运行时。默认从 Maven Central 下载；离线目录通过 `-ProbolectricDependencyDir=<目录>` 指定，其中需要相应的 `android-all-instrumented` JAR。

Release 使用忽略的私有签名材料和公开轮换链，生成非调试的正式签名 APK。缺少材料时停止；其他贡献者可照常构建调试包。初始化与恢复见 [SIGNING.md](SIGNING.md)。

签名策略的纯工具检查使用 `python -m unittest discover -s scripts/tests -v`。公开 CI 同时构建调试版和未签名 Release，运行策略检查；正式密钥不进入 CI。

## 图形资源

```powershell
python scripts/generate_brand_assets.py
python scripts/generate_category_icons.py
```

品牌生成器只使用 Python 标准库。分类生成器校验 Lucide SVG 哈希、许可和全部图标键，再生成 Compose 矢量。生成结果与源图形一起提交。

## 贡献流程

修改前阅读[架构](ARCHITECTURE.md)和对应测试。金融语义、存储、导入和系统生命周期的变更应有回归用例。截图使用合成数据，结果保存在 `artifacts/`。提交记录描述最终行为与验证结果。

GitHub Actions 配置位于 `.github/workflows/verify.yml`，使用固定提交的工具步骤执行源码审查、测试、lint 和构建。原生模拟器与真实设备检查按[验证说明](TESTING.md)单独执行。
