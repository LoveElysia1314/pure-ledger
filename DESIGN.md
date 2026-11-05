---
name: 纯净记账
description: 面向账目阅读、快速录入和核对的原生 Android 界面
colors:
  primary-light: "#1D6754"
  primary-dark: "#83D6B8"
  on-primary-light: "#FFFFFF"
  on-primary-dark: "#0C3527"
  primary-container-light: "#DCEFE5"
  primary-container-dark: "#254C3C"
  on-primary-container-light: "#174736"
  on-primary-container-dark: "#C5EEDB"
  background-light: "#F5F7F5"
  background-dark: "#101613"
  surface-low-light: "#FFFFFF"
  surface-low-dark: "#1A231E"
  surface-container-light: "#EDF1EE"
  surface-container-dark: "#222D26"
  surface-high-light: "#E8EDE9"
  surface-high-dark: "#28332D"
  surface-highest-light: "#E0E7E2"
  surface-highest-dark: "#303D35"
  text-light: "#19231E"
  text-dark: "#E9F0EC"
  text-secondary-light: "#506057"
  text-secondary-dark: "#B8C8BE"
  outline-light: "#718078"
  outline-dark: "#879B8F"
  outline-variant-light: "#D5DED7"
  outline-variant-dark: "#39493F"
  tertiary-light: "#4B636A"
  tertiary-dark: "#ADCBD3"
  expense-light: "#A34537"
  expense-dark: "#EFA596"
  income-light: "#1D6754"
  income-dark: "#83D6B8"
  transfer-light: "#376A86"
  transfer-dark: "#A6CADF"
  brand-ink: "#24594A"
  brand-paper: "#F6F1E6"
  brand-fold: "#AACAAF"
typography:
  headline-large: { fontFamily: "sans-serif", fontSize: "32sp", fontWeight: 600, lineHeight: "40sp", letterSpacing: "-0.7sp", fontFeature: "tnum" }
  headline-small: { fontFamily: "sans-serif", fontSize: "27sp", fontWeight: 600, lineHeight: "35sp", letterSpacing: "-0.4sp", fontFeature: "tnum" }
  title-large: { fontFamily: "sans-serif", fontSize: "21sp", fontWeight: 600, lineHeight: "29sp", fontFeature: "tnum" }
  title-medium: { fontFamily: "sans-serif", fontSize: "17sp", fontWeight: 500, lineHeight: "24sp", fontFeature: "tnum" }
  title-small: { fontFamily: "sans-serif", fontSize: "15sp", fontWeight: 500, lineHeight: "22sp", fontFeature: "tnum" }
  body-large: { fontFamily: "sans-serif", fontSize: "15sp", fontWeight: 400, lineHeight: "23sp", fontFeature: "tnum" }
  body-medium: { fontFamily: "sans-serif", fontSize: "14sp", fontWeight: 400, lineHeight: "21sp", fontFeature: "tnum" }
  body-small: { fontFamily: "sans-serif", fontSize: "12sp", fontWeight: 400, lineHeight: "18sp", fontFeature: "tnum" }
  label-large: { fontFamily: "sans-serif", fontSize: "14sp", fontWeight: 500, lineHeight: "20sp", fontFeature: "tnum" }
  label-medium: { fontFamily: "sans-serif", fontSize: "12sp", fontWeight: 500, lineHeight: "18sp", fontFeature: "tnum" }
  label-small: { fontFamily: "sans-serif", fontSize: "11sp", fontWeight: 400, lineHeight: "16sp", fontFeature: "tnum" }
  entry-amount: { fontFamily: "sans-serif", fontSize: "24sp", fontWeight: 600, lineHeight: "30sp", fontFeature: "tnum" }
rounded:
  key: "8dp"
  tag: "10dp"
  selection: "10dp"
  action: "12dp"
  field: "12dp"
  group: "16dp"
  large: "22dp"
  sheet-top: "24dp"
spacing:
  tight: "4dp"
  related: "8dp"
  group: "12dp"
  page: "16dp"
  touch: "48dp"
  transaction: "64dp"
components:
  button-primary: { backgroundColor: "{colors.primary-light}", textColor: "{colors.on-primary-light}", typography: "{typography.label-large}", rounded: "{rounded.action}", height: "{spacing.touch}" }
  button-text: { textColor: "{colors.primary-light}", typography: "{typography.label-large}", height: "{spacing.touch}" }
  book-menu: { backgroundColor: "{colors.surface-container-light}", textColor: "{colors.text-light}", typography: "{typography.title-small}", rounded: "{rounded.action}", padding: "0dp 12dp", height: "{spacing.touch}" }
  period-selector: { backgroundColor: "{colors.surface-high-light}", textColor: "{colors.text-light}", typography: "{typography.title-small}", rounded: "{rounded.action}", padding: "4dp 12dp", height: "{spacing.touch}" }
  ledger-tabs: { textColor: "{colors.primary-light}", typography: "{typography.title-small}", padding: "10dp 8dp" }
  statistics-segment-selected: { backgroundColor: "{colors.primary-container-light}", textColor: "{colors.on-primary-container-light}", typography: "{typography.label-large}", rounded: "{rounded.key}", height: "32dp", touchHeight: "{spacing.touch}" }
  view-switch-selected: { backgroundColor: "{colors.primary-container-light}", textColor: "{colors.on-primary-container-light}", rounded: "{rounded.key}", height: "32dp", touchHeight: "{spacing.touch}" }
  entry-tag: { backgroundColor: "{colors.surface-low-light}", textColor: "{colors.text-light}", typography: "{typography.label-large}", rounded: "{rounded.tag}", padding: "6dp 10dp" }
  entry-note: { backgroundColor: "{colors.surface-container-light}", textColor: "{colors.text-light}", typography: "{typography.body-large}", rounded: "{rounded.field}", padding: "10dp" }
  candidate-panel: { backgroundColor: "{colors.surface-low-light}", textColor: "{colors.text-light}" }
---

# 纯净记账界面设计

## Overview

**Creative North Star: "账目可读，操作可达"**

界面围绕查看账目、核对金额和完成录入组织。主标题优先显示备注，备注为空时显示交易对象；金额与标题构成第一层信息，时间、账户和分类说明交易背景。分类图标帮助定位，日期分组与期间汇总支持连续浏览。

原生 Compose 控件沿用 Android 的选择、输入、返回和无障碍语义。色面、间距和对齐划分内容层级，主要操作使用主色；账务含义由文字、符号和独立语义色共同表达。

**Key Characteristics:**

- 备注优先，金额完整，辅助信息可连续浏览。
- 色面形成层级，功能边界与组内分隔强度不同。
- 操作目标至少 48dp，阅读内容随系统字号调整。
- 手机竖向操作，宽窗口保留列表与详情之间的连续关系。

规范中的尺寸使用 Android 的 dp 和 sp。颜色、字体与通用形状取自 `LedgerTheme.kt`；语义色、组件形状与间距取自 `LedgerTokens.kt`。平台窗口颜色与 Compose 保持一致。`.impeccable/design.json` 的派生色阶与 HTML/CSS 仅演示 token 和组件结构，原生布局与交互以 Compose 和 Android 截图复核为准。

## Colors

### Primary

绿色主色用于保存、当前标签、选中轮廓和关键图标。账单新增采用普通“+”图标按钮。选中单元采用主色容器及其配对文字色，普通内容保持中性色。品牌图形的墨绿、纸色与折角色独立保留，不随界面主色替换。

### Tertiary

蓝灰辅助色用于识别状态等次要提示。`FeedbackColors` 为成功提供绿色容器、普通信息提供蓝灰容器、警告提供琥珀色容器，错误沿用 Material 错误角色；每种反馈都有明暗容器与配对文字色。

重复账单使用红色文字，展开、收起和完整确认表单保持一致，不增加底色。候选准备失败及通知未获允许使用错误色，保存与重试操作沿用操作色。

### Neutral

背景承载页面，低位色面承载账单日期组、设置组和悬浮面板。容器色面用于账本选择器、备注输入和数字键盘底座；高位色面用于期间选择器、编辑区域及运算键，最高位色面用于进度轨道等内部层次。明暗主题使用各自的配对色值。

`outline` 表达需要辨认的功能边界，如输入轮廓和悬浮窗外缘。`outlineVariant` 用于日期组内部、设置组内部及图表参考线等弱分隔，不承担主要功能边界。正文、输入提示与背景的对比度目标至少 4.5:1，大字号文字至少 3:1。

### Financial

`FinancialColors` 单独定义支出、收入和转账，不借用错误色。账单金额、期间汇总与趋势图共用财务语义；分类占比使用独立分类色板。金额同时保留收支符号或明确标签，颜色不单独承担含义。

## Typography

使用 Android 系统无衬线字体和 Material 字体角色。应用定义的全部字体角色启用等宽数字特性（`tnum`），支持金额、日期和数量的稳定对齐；不把全部正文改成等宽字体。

大标题用于详情金额等重点内容，标题用于页面、账目金额和选择器，正文用于备注及账单内容，标签用于时间、状态和辅助信息。具体字号、字重和行高以 frontmatter 为准，录入金额采用独立的 `entry-amount` 角色。

金额、备注、账单正文和错误信息完整跟随系统字号。短标签通过 `ControlScale` 最多放大至 1.25 倍；期间选择器和内置数字键盘使用固定控件字号。该限制只作用于短控件，不扩展到阅读与编辑正文。

金额不缩小、不省略。空间不足时换行、增加行高或改为上下排列；汇总列数根据可用宽度调整。备注和交易对象可在列表中占两行，详情及编辑器提供完整内容。

## Layout

页面使用紧凑的间距节奏，相关项、内容组和页面边距分别由共享 spacing token 管理。账单条目的最小高度为 transaction，交互目标的最小尺寸为 touch；可见图标和色块可以更小，触控区域保持完整。

手机最小宽度小于 600dp 时请求竖屏，主导航包含账单、统计和设置。窗口宽度达到 600dp 后改用侧栏，录入页可将分类与编辑区并排。账单的双栏由 Material Adaptive 同时依据宽高与分隔式铰链决定，列表偏好宽度为 440dp；高度小于 480dp 时回到单页。收窄后保留选中账目和返回路径。编辑页保留独立标题与固定完成入口。

浏览工具栏在可用宽度不足 760dp 时固定两行，第一行放账本、新增与筛选，第二行放期间导航；更宽时合成一行。新增“+”仅在账单页显示；公共操作保留相同位置，期间变化不改变工具栏结构。

金额标签与数值在同一行按基线对齐。录入金额根据实际文字宽度决定同行或上下排列；期间汇总以 260dp 目标列宽分配一至三列，大字体时提高目标列宽。分类、账目和统计中的长金额保留完整显示，不强制三列。

录入标签采用 `FlowRow`，随实际宽度换成完整行，不依赖横向翻页。长名称最多显示两行，控件状态描述保留完整值。窄窗口手动录入的控制区最高占剩余工作区高度的 66%，大字体时为 82%，优先容纳信息区中常见的两行标签。信息区与分类区独立滚动，保存入口固定；悬浮窗标签固定在保存区上方，输入法打开后仍可保存。

分类提供自动、网格、列表三种偏好。自动模式在英文下使用图标与名称同行的列表，其余语言使用网格；显式偏好独立于语言保存。列数依据可用宽度与内容调整，名称完整换行。

## Elevation & Depth

页面内容通过不透明色面、间距和对齐形成深度。日期组的首尾圆角连成共享容器，内部使用弱分隔；账目仍按单条虚拟化并保留稳定编号。设置组使用缩进的弱分隔线（0.5dp）。

候选悬浮窗底部停靠，默认展开并保留原支付页面的可见区域。主面板最高占可用高度的 72%，次级字段编辑最高为 80%；面板使用中性外缘（1dp，outline 透明度 65%）和阴影高度（8dp）。取消、保存和展开／收起均有独立触控目标。表单底部操作区可使用较低的阴影高度（2dp）区分可滚动内容。

## Shapes

形状按用途区分：数字键帽最紧凑，标签与选择单元稍圆，动作与输入框使用统一圆角，内容组采用更大的圆角。Material 的小形状与输入、动作一致，中形状与内容组一致，大形状用于详情等较大容器。

悬浮面板上缘使用 sheet-top，下缘使用 group，形成顶部柔和、底部稳定的停靠形状。录入控制区顶部使用较大的圆角（20dp），与分类区之间形成明确分区。轮廓强度随功能选择，不为每个内容容器增加边框。

双栏账页图形用于桌面图标、悬浮标题和关于页；桌面图标保留自适应前景、背景和主题单色图层。系统通知使用独立的简化单色轮廓。品牌图形的几何和固有颜色保持一致。

## Components

### 浏览选择器

`BookMenuButton` 以内容宽度呈现左对齐账本名，使用容器色面、下拉图标和独立状态描述。外层分配工具栏余量，按钮本体不填满空余宽度。`SelectorButton` 的期间版本占据前后导航之间的固定区域，使用高位色面、两行文字和下拉图标。两者高度均为 touch，账本名超长时视觉省略，辅助状态仍提供完整名称。

浏览期间以月份为最小单位，提供本月、上月、今年、全部时间和月份区间。整月、整年按日历单位前后切换；单笔交易仍保留精确日期和时分。年月、日期和时分选择复用 Android NumberPicker 滚轮，居中吸附并提供独立字段标签；短窗口保持滚动与输入模式，取消保留原值。自选期间入口名为“自定义”，起止单位仍为月份。

### 操作与输入

主保存使用实色按钮，取消使用文字按钮。内置数字键盘行高为 touch，数字、运算与保存采用不同色面；空间不足时前三行可滚动，最后一行保持可达。

备注是直接输入控件，普通态使用容器色面和透明轮廓，聚焦时显示主色轮廓（2dp）。手动新增首次进入聚焦备注，点击金额转入内置数字键盘；识别候选使用系统数字键盘。交易对象作为独立字段保留，备注不覆盖交易对象语义。

录入标签分别表达时间、账本、账户、交易对象和余额策略。可展开标签带下拉图标，余额策略直接切换；名称可在两行后视觉省略，但无障碍状态提供完整值。`EntryInformation` 在内容确有溢出时显示持久滚动拇指，使用 outline 语义色，宽度为 2dp、高度至少为 16dp，提示信息区仍可继续滚动。打开编辑器前清除输入焦点，返回后保留录入状态。

### 分类与候选

分类在录入页和候选悬浮窗中默认展开。列表选中项采用主色容器与勾选，未选中项保留裸图标；网格按同一选择语义显示状态。搜索入口与当前收支类型位于分类区上方。

分类管理分为启用、隐藏两组，支持网格或同行条目、长按拖动、换组、边缘滚动和批量选择。名称与图标在同一编辑器修改，恢复默认目录保留自定义分类。短名称沿用熟悉用词，同一收支页的默认分类使用不同图标轮廓。分类与账户均独立于账本；账户删除保留历史明细和余额，恢复入口位于已删除列表。

候选悬浮窗在同一窗口中容纳应用标识、金额、备注、分类、标签和确认操作，默认展开。用户主动收起后显示金额及交易对象摘要，并提供展开和取消入口；摘要状态与表单状态共同保留。

### 账单与统计

日期吸附栏处于列表上层，依据顶部可见分组更新文字；流中的日期栏从其下方通过，取消新旧栏互相顶动。账单与统计共用 PeriodOverview，容器水平边距均为 12dp。账单按日期分组，备注优先作为标题；无备注时显示交易对象。常规金额与标题对齐，大字体或长金额改为上下排列。分类图标、时间、账户与分类名称提供辅助定位，重复信息不反复显示。

统计分析与展示方式位于同一行，使用共用轨道与选中色面的分段控件。常规轨道高 36dp、选中色面高 32dp，触控目标至少 48dp；长文字可以增加行高。分类占比和收支变化共用一个图表／列表状态，切换分析维度保留展示方式。

分类收支切换位于环图中央，名称旁的双向箭头表达切换；整个中央信息区可点击。列表模式在分类内容区提供同一收支入口。已限定收支类型的筛选保持其范围，收支变化同时展示当前查询中的财务类型。

分类占比使用环图及对应图例，超过八类时保留前七类并合并余项，点击合并项显示完整列表。手机环图收紧，长金额或大字体时将合计移到图外；宽窗口并排展示环图与自适应图例。收支变化提供图表和分组列表，跨月按月汇总，月内显示有交易的日期；图表补齐空日期，长区间按月合并至最多 60 个时间段。图形使用浮点比例，金额和合计始终以整数分计算。

### 状态与确认

筛选以草稿编辑，应用后生效；账单和统计共用筛选作用域。错误说明具体结果与可执行的恢复动作，财务确认保留金额、引用关系和余额后果。权限用所属功能的状态和设置入口表达，版本集中在关于页，停用功能隐藏入口。语言选项使用四种语言的自身名称，选择后立即生效。

## Do's and Don'ts

### Do

- **Do** 完整显示金额，按宽度换行或调整行列，并保留系统字号。
- **Do** 让备注、交易对象、账户和余额策略各自表达真实字段含义。
- **Do** 为图标和短标签保留至少 48dp 触控目标及明确的辅助状态。
- **Do** 用色面层级组织内容，用功能轮廓与弱分隔表达不同边界。
- **Do** 在手机、大字体、英文和宽窗口中复核同一流程的可达性。

### Don't

- **Don't** 为固定三列、单行或紧凑外观缩小或省略金额。
- **Don't** 将支出套用错误色，或仅靠颜色区分账务类型与状态。
- **Don't** 默认收起候选或分类，增加确认账目前的额外展开步骤。
- **Don't** 用小于 48dp 的点击区换取视觉密度。
- **Don't** 在产品中加入开发答复、验收结论、无关保证或重复版本标签。
