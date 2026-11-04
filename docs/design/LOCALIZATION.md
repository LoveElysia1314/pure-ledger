# 界面语言

应用提供简体中文、繁體中文、English、日本語，默认跟随系统。设置内的语言选择器使用各语言自身名称，选择后立即生效。Android 13 以上通过 LocaleManager 同步系统单应用语言；Android 8–12L 保存应用偏好并创建相应资源上下文。

应用字符串在四种语言中保持相同资源键和格式参数。简体、繁体分别提供 zh-Hans、zh-Hant 资源；Gradle 过滤依赖库中的其他语言资源。默认分类按稳定来源标识生成当前语言的显示名；只有存储名仍为四种语言之一的默认名称时才应用翻译。用户改名、自定义分类、商户和备注保留原文，语言切换不改写数据或导入凭据。分类搜索同时匹配存储名与当前显示名。

AppStrings 供 Activity、服务悬浮窗和普通标签函数读取资源，配置变化触发重绘和通知更新。表单按稳定类型与 ID 保存草稿。原生检查通过实际语言选择器切换，并恢复应用语言和显示配置。

## 支付页面

界面语言与解析规则分别工作。当前解析器处理中文、英文及混合字段的普通人民币收支详情。时间采用无歧义的年在前日期、英文月名、24 小时或 AM/PM；页内未展示的交易号保持为空。

微信适配 Merchant、Transaction Order No. 等字段。支付宝区分主金额和优惠行，已付款等待收货的支出需同时具备明确金额、付款时间和详情上下文。

参考：[Android 本地化](https://developer.android.com/guide/topics/resources/localization)、[单应用语言](https://developer.android.com/guide/topics/resources/app-languages)。
