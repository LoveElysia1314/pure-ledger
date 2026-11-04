# 纯净记账 · Pure Ledger

[![Verify](https://github.com/LoveElysia1314/pure-ledger/actions/workflows/verify.yml/badge.svg?branch=main)](https://github.com/LoveElysia1314/pure-ledger/actions/workflows/verify.yml)

在 Android 设备上记录收支、转账与账户余额。微信、支付宝交易详情可通过无障碍服务生成待确认账单，核对后保存；金额按人民币整数分处理。

## 下载与使用

- [GitHub 发行版](https://github.com/LoveElysia1314/pure-ledger/releases/latest) · [Gitee 发行版](https://gitee.com/LoveElysia1314/pure-ledger/releases)
- 支持 Android 8.0 及以上，安装包名固定为 `io.github.localledger.app`。
- [使用说明](docs/USAGE.md) · [从其他账本迁移](docs/IMPORT.md) · [隐私说明](PRIVACY.md)

首次公开版本为 **0.1.0**。已有开发版可手动下载正式 APK 覆盖安装，保留原有账目和设置；无需卸载或导入恢复。Android 内部构建号独立递增，签名兼容规则见[覆盖升级](docs/SIGNING.md)。原开发版的更新比较逻辑可能不会提示这次公开版本重置，首次请手动下载；公开版本后续按 0.1.x 检测更新。

## 功能

- 收入、支出、账户间转账、连续录入及账户余额核对。
- 多账本、全局账户与分类、分类排序与隐藏、月份浏览、筛选及统计钻取。
- 中文、英文微信和支付宝普通人民币收支详情识别，确认后入账。
- 指定格式 XLSX 预览导入，重复及冲突处理，本软件 ZIP 数据迁移。
- 简体中文、繁體中文、English、日本語，明暗主题、大字体及宽窗口布局。
- 可选状态通知与 Gitee／GitHub 双源更新检测，自动检测可关闭。

导入结构、金额与日期规则、AI 转换提示词见[迁移文档](docs/IMPORT.md)。退款与报销关联、完整设置与附件迁移、新版 XLSX 导出等后续功能见[路线](docs/ROADMAP.md)。

## 构建

需要 JDK 21、Android SDK Platform 36、Build Tools 36.0.0；Gradle Wrapper 固定为 9.1.0。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug
python -m unittest discover -s scripts/tests -v
```

Linux、macOS 使用 `./gradlew`。调试包位于 `app/build/outputs/apk/debug/app-debug.apk`；正式签名构建与密钥管理见[发布流程](docs/RELEASING.md)。

| 目录 | 内容 |
| --- | --- |
| `app/` | Android 界面、存储、无障碍与系统交互 |
| `core/` | 金额、交易、解析、导入及纯逻辑测试 |
| `docs/` | 使用、迁移、架构、验证及发布说明 |
| `examples/` | 可供转换工具参考的合成字段示例 |
| `scripts/` | 构建、签名与验证工具 |
| `design/`、`legal/`、`signing/` | 图形来源、第三方许可及公开证书 |

## 许可与贡献

项目源码及自绘品牌图形采用 [GPL-3.0-only](LICENSE)。第三方组件保留各自许可，见[依赖说明](legal/DEPENDENCIES.md)与[来源记录](ORIGINS.md)。贡献前阅读 [CONTRIBUTING.md](CONTRIBUTING.md)；文档完整入口为 [docs/README.md](docs/README.md)。
