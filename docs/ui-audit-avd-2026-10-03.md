# Prunoid UI 真机走查审计与修改意见（AVD 篇）

> 审查日期：2026-10-03 · 方式：AVD 真机走查（A15_clean 实例，API 35，1080x2400；本仓 assembleDebug 构建 v0.16.1-20261003-112756 → force-stop 后 `am start --activity-clear-task` 冷启 → 确定性坐标逐页导航 → 逐屏截图 → 读图评审；异常以 `logcat -b crash` 定性）
> 姊妹文档：同日**静态代码审计**见本目录 `ui-audit-2026-10-03.md`（代码层问题 P-1~P-12）；三项目横评总报告见 `C:\Users\deser\Projects\android-ui-review-2026-10-03-avd.md`
> 证据截图：`C:\Users\deser\Projects\android-ui-review-2026-10-03\prunoid\`（下文以 `prunoid/NN` 简称，共 24 张）
> 环境说明：走查在**无 root 的 clean 实例**上做——正好覆盖"未 root 用户"场景，本次 P1 即该场景暴露；扫描功能以同实例装/卸 OptIcon 作扫描对象做了对照实验
> 状态：全程零 crash；已与静态篇同批提交入库（2026-10-03）

## 一、问题清单（按严重度）

| # | 严重度 | 问题 | 证据 | 与静态篇关系 |
|---|---|---|---|---|
| B-1 | **P1** | **无 root 环境下核心按钮 "Apply selected (N)" 零反馈死按钮**：勾选 1 个 SDK → 点 "Apply selected (1)" 无任何反应（截图字节级不变）；Select all → "Apply selected (4)" → 仍无反应；crash 缓冲干净。按钮禁用样式与可用态肉眼难辨，界面无任何"为什么不可用"的解释。同时 Settings → Working mode 段选仍显示 "✓ Root — Writes disable rules directly (IFW / pm). Full capability. Default."，与设备实际能力相悖；首页横幅也只说 "blocking does not work" 没说按钮为什么不响应。用户视角：选完点不动，像应用坏了 | prunoid/15→17、18→19（点击前后零变化）、prunoid/09（Working mode） | 静态篇 P 系未覆盖（运行时能力状态问题） |
| B-2 | **P2** | **下拉扫描在空列表态完全失效（非空列表正常）**：首启列表为空时，7 次下拉（起点 y=800~1500、600-800ms、引导卡收起前后）全部无 spinner、无日志（`logcat --pid` 为空）、列表与 footer 零变化；**同样手势在非空列表上工作正常**（卸载一个应用后两次下拉即从列表消失、footer 2→1）。疑似空态视图替换/遮挡了下拉刷新宿主容器。引导卡第一步宣传的恰是 "Pull down to scan"，而首启恰好就是空态——新用户第一次照做必然失败 | prunoid/03、04、05、08（空态 7 连失败）vs prunoid/23→24（非空成功） | 静态篇未覆盖 |
| B-3 | **P2** | Settings → Auto re-apply 行说明文字与尾随开关间距不足：第一行文字延伸到开关正下方（"…after app install/" 贴住开关） | prunoid/10 | — |
| B-4 | P3 | 首启 root 警示同文案同时以横幅+snackbar 两份出现（后续每次冷启仍弹 snackbar） | prunoid/01、02、14、22 | — |
| B-5 | P3 | "Nothing to show" 空态无任何操作提示——而 B-2 使下拉恰在此态失效，空态用户彻底无路可走 | prunoid/03 | 与 B-2 联动 |
| B-6 | P3 | Working mode 段选反映的是配置而非当前能力（显示 Root/Full capability 而设备无 root） | prunoid/09 | B-1 的一部分 |

## 二、修改意见（按建议施工顺序）

1. **B-1 死按钮（P1，最高优先）**：三层修复——
   - 无 root（或 Working mode=Root 但授权失败）时 Apply 按钮禁用要有**视觉上明确可分的禁用态** + 按钮下方一行原因（如 "需要 root 授权后可用 / Grant root access to apply rules"）；
   - Working mode 段选**如实反映当前能力**：探测不到 root 时自动落到 "Audit only" 并标注原因，而不是显示 "Full capability"；
   - 或至少点击禁用按钮时弹 Snackbar 说明原因（零反馈比禁用更糟）。
2. **B-2 空态下拉（P2）**：让下拉刷新手势宿主**包住空态视图**（空态与列表同住一个可滚容器，而不是条件分支互相替换）；顺带加扫描中指示器（spinner 或波浪进度），让"扫了但没结果"与"没触发"可区分。注意静态篇 P-7 的教训同源：空态分支替换宿主容器是本项目已知模式，修复时全局 grep 一次同类结构。
3. **B-5 顺路**：空态文案补一句操作指引（"Install apps then pull down to scan / 下拉扫描或点击右上角刷新"）——与 B-2 修复天然同点。
4. **B-3 行距（P2）**：说明文字 `weight(1f)` 占满开关左侧剩余宽度，或行改上下结构（文字在上、开关行在下）。
5. **B-4/B-6（P3）**：snackbar 与横幅二选一（建议留横幅）；Working mode 随 B-1 一并修。

## 三、真机验证通过项（回归基线，改动后别退化）

- **"On open" 补扫正常**：装新应用后冷启即出现在列表（OptIcon 4 SDKs 入列，prunoid/14）——B-2 修复别动这条路径；
- App 详情页工作台信息完整：版本/Target·Min/体积/安装时间、IFW 与 pm 双引擎说明、SDK/组件双视图、Select all/none 生效、未选择时 Apply 文案置灰逻辑本身存在（prunoid/15、16、18）；
- SDK library（Found 31/library 1288、Risky to disable 标签，prunoid/12）与 Stats（326 SDKs/241 apps、环形图、Top10，prunoid/13）渲染正确；
- 通知权限首启请求、语言三段切换、深色模式全量适配（prunoid/20）、开源署名+AI 披露完整（prunoid/11）；
- 冷启/导航/tab 切换全程零 crash。

## 四、修复验证建议

B-1：clean 非 root 实例复验——进入详情页勾选 SDK，观察按钮禁用样式可辨、原因行出现；在 root 实例（SDK-Pruner_A16_root 同类环境）复验正向路径 Apply 弹量化确认框（确认交互本身静态篇已设计好，别回归）。
B-2：**空列表态**下拉必须出扫描指示且列表可刷新（本次 7 连失败的起点/时长参数可直接复用：`input swipe 540 1300 540 2200 700`）；非空列表下拉回归通过后再收工。建议修复后人工手感复验一次（自动化 swipe 与真人手感有差异，本次结论已据此措辞）。
