# 正式签名与保留数据升级

## 安装身份和证书策略

应用包名固定为 `io.github.localledger.app`，完整无障碍组件为 `io.github.localledger.app/io.github.localledger.app.accessibility.LedgerAccessibilityService`。0.1.0 的 Android 构建号为 60400，高于开发版 0.6.3 的 60300；数据库仍为 v9，ZIP 仍写 v5／读 v1–v5。

发行 APK 同时包含兼容签名和证书轮换证明，由 Android 按系统版本选择证书：

| 系统 | 实际使用的签名 |
| --- | --- |
| Android 8.0–12L，API26–32 | 保留原有开发安装使用的证书 |
| Android 13 及以上，API33 起 | 新的 Pure Ledger 正式发行证书，附带原证书的继承证明 |

Android 13 起使用 v3.1 轮换；较早系统继续使用原密钥签署的兼容签名。该策略依照 [Android apksigner 文档](https://developer.android.com/tools/apksigner)和 [v3.1 签名方案](https://source.android.com/docs/security/features/apksigning/v3-1)。

旧证书仅保留已安装数据和签名权限继承能力。权限继承用于延续包括 AndroidX 接收器权限在内的应用自有签名权限；关闭这一能力会导致系统拒绝更新。shared UID、认证器特权和旧证书回退能力均关闭。Android 13 起，正式升级后仅由旧证书签名的 APK 不得覆盖新版本；较早系统仍需长期保护原密钥。

## 公开验证资料

| 文件 | 用途 |
| --- | --- |
| `signing/legacy-certificate.der` | 原有安装证书，不含私钥 |
| `signing/release-certificate.der` | 正式发行证书，不含私钥 |
| `signing/release.lineage` | 公开的证书轮换证明，不含私钥 |
| `signing/certificates.json` | 包名、证书 SHA-256、轮换阈值和能力约定 |

正式证书 SHA-256：`3b7f0b9a5ec91d02c0a75e54a5a575b1673f2e89447745fe2bbeb1e2a0fa7889`。

原有证书 SHA-256：`1fab5639917a71e5490b0ab426bc182e2d10077503d2bfe44f471b5fafb60341`。

验证者仅需 JDK 21、Android Build Tools 36.0.0 和公开仓库，即可验证发行包；验证过程不读取私钥或口令：

```powershell
python scripts/release.py verify --apk artifacts/pure-ledger-0.1.0.apk
```

验证覆盖 API26–32、API33–36 的适用证书、APK 内完整轮换链与公开文件一致、禁止旧签名回退、非调试构建、16KB ZIP 对齐及许可资产。验证通过表示所列检查通过；设备实际安装及账目保留另有升级检查记录。

## 私有材料与恢复

正式密钥为独立 RSA4096 密钥，PKCS12 存储，由随机高熵口令保护。原有开发证书的私钥单独保留，不使用过渡应用或旧应用的发行密钥。

以下文件仅保存在被 Git 忽略的 `.private/signing/`：

- `pure-ledger-release.p12`：正式私钥。
- `legacy-debug.keystore`：原有私钥的固定副本。
- `credentials.json`：本机签名口令配置，不输出到构建日志或命令参数。
- `backup/`：两份加密密钥文件、轮换链及 SHA256SUMS 的本机恢复副本。
- `recovery-credentials.txt`：恢复口令记录，应与密钥备份分开保管。

Windows 初始化时移除私有签名目录的继承权限，仅向当前用户和 Local System 授权；其他系统使用私有目录权限。签名工具通过子进程环境传入口令。源码、源码 ZIP 和公开构建日志均不包含私有材料。

本机恢复副本不能抵御整机或磁盘损坏。维护者应将 `backup/` 复制到受控离线介质，并将恢复口令单独保管。丢失私钥会影响后续更新；不得通过重新生成同名密钥代替恢复。

`python scripts/release.py init` 为一次性初始化入口。已有完整材料时只校验证书与密钥一致性；存在部分材料或已有公开身份而私钥缺失时停止，不自动覆盖或重新生成。恢复时应还原原有密钥和口令，再运行该命令校验。

## 正式构建

```powershell
scripts/Project.ps1 Release
# 或跨平台执行
python scripts/release.py build
```

流程先核对私有材料和公开身份，执行 assembleRelease 与 lintRelease，对齐后生成 v2／v3／v3.1 签名，再校验证书和轮换链。缺少密钥、证书不符、输出文件已存在或签名失败时立即停止，不退回调试签名。

产物为 `artifacts/pure-ledger-<版本>.apk` 和 `.apk.sha256`，报告为 `artifacts/validation/signing/release-signing.json`。直接运行 Gradle assembleRelease 得到的 `app-release-unsigned.apk` 仅为签名前中间产物，不用于分发。正式包关闭 debuggable，排除仅调试使用的界面工具。

两端发行应上传同一个 APK、对应提交源码和校验值。提交后执行 `python scripts/package_source.py --ref <提交或标签>` 核对源码包。构建、提交或生成本地源码包都不会自动推送、发布或上传私钥。

## 覆盖升级验证

`scripts/run_upgrade_checks.py` 仅允许指定的 LocalLedger_API35 专用 API35 模拟器，拒绝真实手机和其他 AVD。测试支持从原开发签名或同一正式签名的已有版本直接覆盖安装 0.1.0，保留原包名和 UID，不执行卸载、清数据、降级安装或导入恢复。

测试使用合成账本，包含整数分金额、大余额、收支、带手续费的转账、识别凭据、选择学习、隐藏分类和删除标记。逐项比较全部数据库表结构与内容、外键、数据库版本、所有 SharedPreferences、持久文件与 UID，另核对通知、悬浮窗和完整无障碍组件的权限状态。随后尝试仅旧证书签名的同版本包，要求系统拒绝，再确认正式包和全部数据仍在。

```powershell
python scripts/run_upgrade_checks.py --serial emulator-5580 --adb <ADB完整路径> --baseline-apk <已有安装对应的APK> --release-apk artifacts/pure-ledger-0.1.0.apk --output artifacts/validation/signing/upgrade
```

原生证据仅代表 API35 专用模拟器。API26–32 的证书适配使用签名工具进行验证，真实手机、厂商安装器、实际无障碍权限和账目核对仍需单独验收。

真实手机升级前读取安装包名、版本和证书，确认原证书属于上述继承链且构建号低于目标版本。可额外通过应用主动导出保管现有账目；覆盖升级自身直接保留应用数据，不以导入来替代保留数据验证。原版和过渡应用不参与本次升级，也不清除或卸载。

正式升级后的 Android 13 及以上设备继续使用同一正式证书和轮换链更新。普通调试 APK 不作为生产设备更新包；新版本按版本规则递增，不用删除现有应用来解决签名冲突。
