# DiPlay CN 优化日志

本仓库相对官方 DiPlay 的改动单独记在这里。官方上游版本见 [CHANGELOG.md](../CHANGELOG.md)。下载页每次发版也会带同一份说明。

覆盖安装：包名始终为 `com.shihab.diplay.cn`，公开 APK 使用同一套 debug 签名。只要新包的 `versionCode` 更大，即可覆盖旧版并保留设置。不能覆盖官方 `com.shihab.diplay`。

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
