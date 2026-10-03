# DiPlay CN 优化日志

本仓库相对官方 DiPlay 的改动单独记在这里。官方上游版本见 [CHANGELOG.md](../CHANGELOG.md)。下载页每次发版也会带同一份说明。

覆盖安装：包名始终为 `com.shihab.diplay.cn`；自 0.2.10-cn.4 起所有 CN 版本使用同一把固定签名密钥，`versionCode` 更大即可直接覆盖安装并保留设置。0.2.10-cn.3 及更早版本签名各不相同，升到 cn.4 需最后一次卸载重装。不能覆盖官方 `com.shihab.diplay`。

## 0.2.10-cn.15 — 2026-10-03

- 新增「CarPlay 期间暂停车机蓝牙 · 需要 ADB」（默认关闭）：解决来电时车机蓝牙电话与 CarPlay 电话同时弹出、需手动选通道的问题。CarPlay 连接期间暂停车机蓝牙，结束自动恢复、下次握手前自动唤醒，配对不受影响。
- APK：见 [v0.2.10-cn.15](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.15)

## 0.2.10-cn.14 — 2026-10-03

- 「开机自启 · ADB 修复（实验）」改为始终显示（此前需先开启「车辆启动后打开」才能看到，入口太隐蔽）。位置：设置 → 自动连接。
- APK：见 [v0.2.10-cn.14](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.14)

## 0.2.10-cn.13 — 2026-10-03

- 仪表通信加固（应对「仪表简易模式」）：「仪表盘歌曲」现在同时受总开关「HUD 和仪表盘导航」控制——关掉总开关即停止**一切**向仪表的写入（导航广播、歌曲、地图暂停读取）。歌曲写入一旦失败立即放弃本会话的后续写入并记录日志，不再反复冲击仪表服务。
- 遇到仪表简易模式的恢复步骤已写入 README（关闭所有仪表相关开关后重启/切换一次仪表主题）。
- APK：见 [v0.2.10-cn.13](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.13)

## 0.2.10-cn.12 — 2026-10-03

- 自定义转向卡下方新增行程信息条：**xx:xx 到达 · xx小时xx分 · xx.x 公里**（预计到达时间、剩余时长、剩余里程，数据来自 CarPlay 导航元数据）。胶囊样式与转向卡一致，宽度对齐、高度随卡缩放，跟随卡片位置移动；无行程数据时自动隐藏。
- APK：已撤回（安装解析问题反馈），功能包含在 [v0.2.10-cn.13](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.13) 中

## 0.2.10-cn.11 — 2026-10-03

- 新增「开机自启 · ADB 修复（实验）」：设置 → 自动连接 → 开机自启开启后出现。通过车机自身 ADB 通道执行 `appops` 后台运行豁免 + doze 白名单，让开机广播能送达（部分固件拦截第三方接收器导致自启失效）。执行一次即可，固件升级后可重跑；比亚迪私有的自启动管理仍需在车机设置中手动放行。
- APK：见 [v0.2.10-cn.11](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.11)

## 0.2.10-cn.10 — 2026-10-03

- 按用户习惯恢复桌面名 **DiPlay CN**，清除此前别名的全部痕迹（应用名、README、发版说明、更新器 UA）。包名与签名不变，仍可直接覆盖安装。
- APK：见 [v0.2.10-cn.10](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.10)

## 0.2.10-cn.9 — 2026-10-03

- 自定义转向卡图标换为 **Material Symbols**（Apache 2.0，google/material-design-icons）：转向/缓转/急转/掉头/直行/环岛/终点全套矢量，系统蓝着色；环岛出口号改为图标右下角的圆形角标。新资源使用 `ic_turn_card_` 前缀，不与官方小组件的 `ic_maneuver_` 图标混用。
- APK：见 [v0.2.10-cn.9](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.9)

## 0.2.10-cn.8 — 2026-10-03

- 应用内更新：关于页「检查更新」读取 GitHub 最新 Release，弹窗后直接下载 APK 并调起系统安装器；同签名覆盖安装，设置保留。`versionName` 改为完整标签（如 0.2.10-cn.8）以便比对。
- 自定义转向卡位置改用滑块（2% 步进），拖动即时生效，替代原来 43 项的长列表弹窗。
- 清理：移除从未成功的 Pages 发布工作流。
- APK：见 [v0.2.10-cn.8](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.8)

## 0.2.10-cn.7 — 2026-10-03

- 桌面名短暂使用过别名（cn.10 已恢复 DiPlay CN）。包名保持 `com.shihab.diplay.cn` 不变，老用户可直接覆盖安装、设置保留。
- APK：见 [v0.2.10-cn.7](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.7)

## 0.2.10-cn.6 — 2026-10-03

- 修复提示语不跟手：CarPlay 连接页的「N 指下滑打开设置」提示此前只在进入时写死为 3 指，改为每次按当前设置刷新（在 DiPlay 设置里改成 2 指后，提示立即显示 2 指）。
- APK：见 [v0.2.10-cn.6](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.6)

## 0.2.10-cn.5 — 2026-10-03

- 「下滑手指数」设置移到更显眼的位置：DiPlay 应用「设置 → CarPlay 操作」第一项即可选择 2/3/4 指（无需连接 CarPlay、无需手势）。此前该设置只在 CarPlay 内部菜单里，而那菜单本身要靠下滑手势打开，在空调占用三指的车机上形成死循环。
- APK：见 [v0.2.10-cn.5](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.5)

## 0.2.10-cn.4 — 2026-10-03

- 修复「每次都要卸载才能安装」：此前 CI 用临时运行器上随机生成的 debug 签名，每个包签名都不同，Android 拒绝覆盖更新。现在仓库内置固定的 CN 签名密钥（`signing/diplay-cn.jks`，专用一次性密钥），此后所有 CN 版本同签名，可直接覆盖安装。
- 注意：从 cn.3 及更早版本升级到本版仍需**最后一次**卸载（签名切换），此后不再需要。
- APK：见 [v0.2.10-cn.4](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.4)

## 0.2.10-cn.3 — 2026-10-03

- 移除悬浮 ⚙ 按钮：CarPlay 主屏图标由 iPhone 渲染且点按无回调，无法在那一排图标里加入口；改为可配置手势——设置菜单新增「打开设置 · N 指下滑（点按切换）」，可选 2/3/4 指，默认 3 指，避开比亚迪三指下滑的空调面板。
- 自定义转向卡重画：急转（如右后方）与缓转（右前方）的箭头语义修正——缓转为约 30° 浅斜线，直角转弯为 90° 折线，急转超过 90° 并略向下指。
- 卡片改为 iOS 风格：深色玻璃胶囊（细描边、无侧边条），箭头放在半透明圆角芯片内，蓝色 #0A84FF，距离加粗、路名次级灰。
- APK：见 [v0.2.10-cn.3](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.3)

## 0.2.10-cn.2 — 2026-10-03

- CarPlay 画面右下角新增半透明 ⚙ 按钮，点按直接打开 DiPlay 设置。比亚迪系统把三指下滑占给了空调面板，原手势在部分车机上不可用；三指手势保留，⚙ 按钮始终可用。
- APK：见 [v0.2.10-cn.2](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.10-cn.2)

## 0.2.10-cn.1 — 2026-10-02

- 基于官方 v0.2.10。版本名 `0.2.10`，`versionCode` 35，标签 `v0.2.10-cn.1`。
- 官方 0.2.10 合并了十个 PR，其中 #133「主屏幕地图与仪表盘同步」来自本仓库的 fork，不再是 CN 差异点。
- 保留全部 CN 差异：仪表盘四种显示（含可挪自定义转向卡，2% 步进）、简体中文回退、无线看门狗跳过、CN 包名与发版流水线。
- APK：[DiPlay-cn-v0.2.10-cn.1.apk](https://github.com/serein-morii/DiPlay-CN/releases/download/v0.2.10-cn.1/DiPlay-cn-v0.2.10-cn.1.apk)

## 0.2.9-cn.1 — 2026-10-02

- 基于官方 v0.2.9。版本名 `0.2.9`，`versionCode` 34，标签 `v0.2.9-cn.1`（沿用官方版号 + `-cn` 的编号方式）。
- 仪表盘四种显示：只地图、只官方转向卡、官方地图+官方玻璃卡、官方地图+自定义可挪转向卡。
- 自定义卡 2% 步进，直行也显示；打开即加载。
- 主屏幕浮动地图卡可与仪表盘同步或分开。
- APK：[DiPlay-cn-v0.2.9-cn.1.apk](https://github.com/serein-morii/DiPlay-CN/releases/download/v0.2.9-cn.1/DiPlay-cn-v0.2.9-cn.1.apk)

## 0.2.8.6 — 2026-10-02

- 版本名 `0.2.8.6`，`versionCode` 32。
- 有导航就显示转向卡：直行也出卡片；未知动作按直行显示，不再当成「无动作」藏起来。
- 重开 CarPlay 不再清空已有导航状态，避免地图出来了转向卡却没了。
- APK：[DiPlay-cn-v0.2.8.6-cn.1.apk](https://github.com/serein-morii/DiPlay-CN/releases/download/v0.2.8.6-cn.1/DiPlay-cn-v0.2.8.6-cn.1.apk)

## 0.2.8.5 — 2026-10-02

- 版本名 `0.2.8.5`，`versionCode` 31。
- 转向提示卡位置按 5% 网格吸附，避免设置和测试超出范围。
- APK：[DiPlay-cn-v0.2.8.5-cn.1.apk](https://github.com/serein-morii/DiPlay-CN/releases/download/v0.2.8.5-cn.1/DiPlay-cn-v0.2.8.5-cn.1.apk)

## 0.2.8.4 — 2026-10-02

- 版本名改为 `0.2.8.n`：官方 0.2.8 之后，CN 每改一版加一位。
- 转向提示卡可按 5% 步进在整个仪表盘上左右、上下移动，不再挤在中间窗口。
- 卡片改为深色玻璃卡和几何箭头（直行、左右转、斜向、掉头、环岛、到达）。
- APK：[v0.2.8.4-cn.1](https://github.com/serein-morii/DiPlay-CN/releases/tag/v0.2.8.4-cn.1)（该标签构建失败，请用 0.2.8.5）

## 0.2.8.3 — 2026-10-02（标签 v0.2.8-cn.3）

- 转向提示卡按仪表盘中间可见窗口缩放，避免「小 + 右」被裁掉、「大」占半屏。
- APK：[DiPlay-cn-v0.2.8-cn.3.apk](https://github.com/serein-morii/DiPlay-CN/releases/download/v0.2.8-cn.3/DiPlay-cn-v0.2.8-cn.3.apk)

## 0.2.8.2 — 2026-10-02（标签 v0.2.8-cn.2）

- 发版改为 `assembleRelease`，体积与官方约 31.4 MB 一致。
- 「地图和转向提示卡」由 DiPlay 叠一层可调位置/大小的转向卡（iPhone 无法把转向卡和车标分开摆）。
- APK：[DiPlay-cn-v0.2.8-cn.2.apk](https://github.com/serein-morii/DiPlay-CN/releases/download/v0.2.8-cn.2/DiPlay-cn-v0.2.8-cn.2.apk)

## 0.2.8.1 — 2026-10-02（标签 v0.2.8-cn.1）

- 同步官方 v0.2.8。
- 包名 `com.shihab.diplay.cn`，桌面名 DiPlay CN，可与官方版并存。
- 车机语言不在支持列表时默认简体中文。
- AirPlay 已起来后跳过无线 handoff watchdog。
- 音频流选择恢复 0–20（官方 0.2.8 仅 0–10）。
- 当时仍打 debug 包，约 40.8 MB。
- APK：[DiPlay-cn-v0.2.8-cn.1.apk](https://github.com/serein-morii/DiPlay-CN/releases/download/v0.2.8-cn.1/DiPlay-cn-v0.2.8-cn.1.apk)

## 0.2.7-cn.1 — 2026-09-30

- 基于官方 v0.2.7 的第一版 CN 构建。
- 独立包名、简体中文回退、无线 watchdog 跳过、从官方 APK 抽取 identity 发版。
- APK：[DiPlay-cn-v0.2.7-cn.1.apk](https://github.com/serein-morii/DiPlay-CN/releases/download/v0.2.7-cn.1/DiPlay-cn-v0.2.7-cn.1.apk)
