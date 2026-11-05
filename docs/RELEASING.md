# 发布流程

## 版本与安装连续性

`gradle.properties` 中的 `versionName` 是公开版本，首发为 0.1.0；`versionCode` 是 Android 安装构建号，首发为 60400。两者用途不同。为兼容已有开发安装，固定 `versionCodeBase=50400`：

```text
versionCode = 50400 + major × 1,000,000 + minor × 10,000 + patch × 100 + versionRevision
```

minor、patch、versionRevision 取 0–99，versionRevision 默认为 0。0.1.1 的初始构建号为 60500，当前同版本替换使用 revision=1、构建号 60501；后续 0.1.2 从 60600 开始，0.2.0 从 70400 开始。不要重设偏移量或降低构建号。应用内更新检测比较公开版本，数据库和 ZIP 使用各自的格式版本。

通常应递增公开版本。同版本覆盖必须有明确授权，并先备份远程引用、提交、发行说明和全部附件；新 APK 仍需递增安装构建号。替换后，已安装同名旧包的设备需手动下载覆盖，常规自动更新不会提示这次替换。

包名、签名与数据升级规则见 [SIGNING.md](SIGNING.md)。公开仓库初始化提交不会改变手机安装身份或签名材料。

## 检查与构建

1. 更新公开版本、构建号和 CHANGELOG，核对源码、素材与第三方许可。
2. 执行 `scripts/Project.ps1 Check`、`python -m unittest discover -s scripts/tests -v` 和 `python scripts/audit_source.py`。
3. 推送到 main，确认 [Verify](https://github.com/LoveElysia1314/pure-ledger/actions/workflows/verify.yml) 对当前提交通过；公开 CI 不读取正式私钥。
4. 用 `scripts/Project.ps1 Release` 或 `python scripts/release.py build` 生成正式签名 APK，再运行 verify 检查。直接 assembleRelease 的文件尚未签名，不用于分发。
5. 在明确指定的专用模拟器核对覆盖升级，按实际设备条件补做真机验收。
6. 对最终提交运行 `python scripts/package_source.py --ref <提交或标签>`，生成源码包并核对文件、哈希及 Wrapper 执行位。

`artifacts/` 保存安装包、源码包和验证结果；`.private/` 保存私钥、口令、回退副本及本机配置，两者均不提交。密钥恢复资料应另存到受控离线介质。

## 双平台发行

GitHub 和 Gitee 使用同一标签 `v0.1.0`、同一源提交、同一正式 APK 及源码 ZIP。发行说明列出功能、Android 8.0 及以上要求、导入格式入口、已知限制、升级规则与 SHA-256。

先准备 APK、源码及 SHA256SUMS，再公开 Release，避免更新检测指向没有安装包的页面。仅推送标签不会产生可读取的发行记录。GitHub 接口见[官方文档](https://docs.github.com/en/rest/releases/releases#list-releases)，Gitee 接口见[官方文档](https://gitee.com/api/v5/swagger)。

发布后应匿名读取两端 Release，检查标签、附件和版本；下载文件重新核对哈希。私钥、恢复口令、真实账目和设备日志不属于发行资源。

## 更新行为

客户端分别读取 Gitee 与 GitHub 的第一页最多 100 条公开 Release，排除草稿、预发行和不合法标签，选择最高稳定版本。同一新版在两端存在时提供两个入口；单源失败不影响另一源，全部失败才反馈，自动检查无新版时保持安静。

回到前台每 24 小时最多自动检查一次；手动检查绕过时间间隔。每源有连接、读取、协程超时及 1 MiB 响应上限。发行按版本递增，避免最新版本被超过 100 个后续记录覆盖。下载通过系统浏览器完成，应用不申请内部安装权限。

原 0.6.x 开发版可能不会自动提示公开版本重置为 0.1.0，首次应手动下载同包覆盖。0.1.0 起的客户端按公开语义版本检测后续更新。
