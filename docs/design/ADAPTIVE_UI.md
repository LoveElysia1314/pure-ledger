# 窗口与内容布局

## 分类与阅读条目

分类布局保存自动、网格、列表三种偏好。自动模式在英文下采用图标与文字同行的列表，在简体中文、繁体中文和日文下采用网格。记账页、悬浮窗和分类管理使用同一偏好。网格与列表均按可用列宽安排项目，长名称完整换行。

统计图例根据可用宽度增加列数。手机环图收紧到 168dp，图心展示收支合计，长金额或大字号时移到图外完整展示。分类占比超过八类时保留前七类并合并其余类别，手机图例按占比排列，点击合并项可查看完整列表。金额、笔数和占比使用完整筛选结果。

期间列表在手机上使用分组卡片，在宽度与字号允许时使用固定列报表，逐行对齐日期、笔数和金额。过长金额回到卡片排布。

## 页面与窗口

手机（最小宽度小于 600dp）请求竖屏，平板和展开后的宽窗口保留系统方向。手机顶栏固定两行；足够宽的窗口采用单行。账本和月份选择器左对齐，以色面和下拉图标表达交互；“+”只在账单页显示，账本、期间及筛选位置在两页保持一致。期间文本在固定区域内展示，范围变化不改变工具栏行数。

浏览期间以月份为最小选择单位，支持年份、全部时间与月份区间。月份区间按完整自然月包含记录，年份和整段月份按日历单位前后切换；交易本身的精确日期、时分及月内日汇总继续保留。汇总以标签和金额同行展示，列数取决于可用宽度，不以金额字符串位数触发整组堆叠。

账单在高度足够的宽窗口中显示列表与详情两栏，未选中账目时右栏显示期间分类占比。窗口变窄后显示已选账目的详情，返回后继续浏览原列表。顶部时间、账本和筛选保持共同作用域。

双栏使用 Material 3 Adaptive Layout，依照窗口尺寸与系统报告的分隔式铰链安排内容。高度小于 480dp 时使用单页；横屏宽度本身不决定双栏。录入页在较宽窗口中并排展示分类与编辑区。

录入标签依据实际宽度排列成完整行，长名称最多占两行并提供完整的辅助状态描述。手动录入的信息区按可用高度滚动，悬浮窗标签固定在保存区上方；分类区独立滚动。输入法打开时保留保存入口，金额和备注继续跟随系统字号。

## 设计参考

[Shirone](https://shirone.mysqil.com/) 将网格、列表偏好与当前可容纳的布局分开处理。此次参考其布局选择与列宽策略，Android 界面采用 Compose 独立实现。研究对应 [layout-mode.ts](https://github.com/LyraVoid/Shirone/blob/b4a9d5679c5460c66ed3a9f1ec644b7df4cc2470/src/utils/layout-mode.ts) 和 [postListConfig.ts](https://github.com/LyraVoid/Shirone/blob/b4a9d5679c5460c66ed3a9f1ec644b7df4cc2470/src/config/postListConfig.ts)。

Android 的[列表与详情布局](https://developer.android.com/develop/adaptive-apps/guides/list-detail)提供宽窗口并排、窄窗口逐页的导航结构；[窗口尺寸分类](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)同时考虑高度；[折叠姿态指南](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware)说明系统铰链信息的使用方式。物理折叠屏的连续折叠、分屏与厂商行为仍需真机复核。

方向策略参考 Android 的[方向与可调整窗口规则](https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability)。大屏保留自适应布局，手机方向策略通过专用模拟器旋转场景验证。

日期吸附使用固定图层，文本按顶部可见分组变化，列表分组栏从图层下方通过。期间与交易时间的滚轮使用相同字号、标签和吸附行为，交易时间编辑继续保留秒和毫秒。
