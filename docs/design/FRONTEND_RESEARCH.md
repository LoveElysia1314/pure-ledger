# 前端技术选择

应用使用 Kotlin、Jetpack Compose、Material 3 和 Navigation Suite。界面直接接入 Android 无障碍服务、系统悬浮窗口、通知、输入法和本地数据库。

Compose 通过声明式状态组织账单、筛选与表单草稿。Material 组件提供日期、时间、对话框和系统外观语义；窗口宽度、字体比例和输入法状态决定具体布局。账单列表采用分页读取，来源字段按需加载。

当前技术选择服务于原生窗口和生命周期的协同。界面演进通过共享字体、颜色、形状、表单和导航组件完成。后续架构调整由可复现的性能、兼容或维护问题驱动。

参考：[Compose](https://developer.android.com/develop/ui/compose)、[Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3)、[自适应导航](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)。

## 日期与统计交互

Android 日期组件提供日历和输入两种选择方式，选择状态由父组件管理。应用保留起止日期草稿，确认后将本地日期转换为含结束日的查询范围；短窗口与大字体采用可滚动的输入方式。[Android 日期选择器](https://developer.android.com/develop/ui/compose/components/datepickers)

Cashew 的统计按选定时间范围组织总览、图形和分类，也提供历史期间比较。这一组织方式支持把筛选作为图表、合计和明细的共同条件。[Cashew 使用指南](https://cashewapp.web.app/faq.html)

当前实现采用分类排行与时间趋势两种视图。趋势图基于完整汇总，提供精确数值、列表替代与期间明细入口；有界区间补齐空日期，长范围按月份分段。时间点的用途是定位可核对的账目，绘图比例与金额显示分别计算。

## 固定日期栏与滚轮

日期栏采用独立的固定图层，按 LazyColumn 顶部可见项目所属分组派生文字状态。位置不参与分组交接动画；派生状态只在分组变化时更新，不延迟日期与账目之间的对应关系。

年月、年月日和时分选择复用 [Android NumberPicker](https://developer.android.com/reference/android/widget/NumberPicker) 的滚动吸附。API 29 及以上使用公开的文字颜色、字号与分隔高度接口接入应用主题，较早系统沿用平台主题。

后台耗电入口优先使用 AOSP 的应用电池详情页面，未提供该页面时依次尝试[电池优化列表与应用详情](https://developer.android.com/reference/android/provider/Settings#ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)。系统优化状态由 PowerManager 读取，厂商附加的后台管理仍需对应设备核对。
