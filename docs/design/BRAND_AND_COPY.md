# 品牌与界面文案

纯净记账使用双栏账页标记，将桌面、无障碍服务、通知、候选窗口和关于页关联为同一应用。品牌颜色和组件规范见 DESIGN.md，图形源文件为 design/brand/mark.svg，生成器为 scripts/generate_brand_assets.py。

## 图标

桌面图形由自适应前景与背景组成，主题单色图层供系统着色。通知栏采用独立 24dp 画布、20dp 主轮廓和较少内部细节，保持小尺寸辨识度。通知强调色使用品牌墨绿，状态文字由当前功能、权限和服务连接决定。

## 文案

控件名称表达动作或结果，例如余额变动、原始记录、图片链接。运行状态使用运行中，服务说明交代当前交易详情的读取与确认保存流程。关于页集中展示图标、名称、版本和许可。

原始记录展示来源应用、真实交易号、交易方、方向、金额和时间。底层证据随数据保存和导出，使用者自定义导入列按原内容展示。删除、合并和余额选项说明对记录与账户的实际影响。

参考：[Android 自适应图标](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)、[通知结构](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications)。
