# DiPlay CN

为兼容的比亚迪安卓车机提供有线 / 无线 CarPlay。基于官方 [DiPlay](https://github.com/shihabal3amri/DiPlay) `v0.2.14`，包名 `com.shihab.diplay.cn`，可与官方版并存安装。

**测试车辆：2025 款比亚迪汉 DM-i，车机 DiLink 5.0。** 其他车型、其他车机系统不保证所有功能可用；请自行测试，或拉取源代码按本车修改。

## 下载

- GitHub：[releases/latest](https://github.com/serein-morii/DiPlay-CN/releases/latest)
- Gitee（国内默认通道）：[releases/latest](https://gitee.com/oneeyear/DiPlay-CN/releases/latest)
- 应用内：关于 → 检查更新（四通道：Gitee / GitHub / gh-proxy.com / ghproxy.net）

当前版本 **0.2.14-cn.6**（versionCode 91）。自 0.2.10-cn.4 起签名固定，新版本直接覆盖安装、设置保留。

## CN 相对官方加了什么

- **自定义仪表转向卡**：大小 30–95%、透明度 20–100%、位置 1% 步进滑条；昼夜玻璃跟随车机；行程信息条（到达 · 时长 · 里程）；断线不清卡、到达终点自动清卡。
- **小屏导航三模式**（关 / 开 / 自动）：仪表切小屏时自动换用小屏位置；自动模式优先 ADB 读方向盘「小屏/全屏导航」，读不到再用使用情况访问。
- **应用内更新** + 四通道下载。
- **开机自启 · ADB 修复**、**CarPlay 期间延时暂停车机蓝牙**（5/10/15/30 秒）、**已选 iPhone 蓝牙重连自动连接**。
- 点 DiPlay 图标回到 CarPlay（可关）、下滑菜单顶部直达完整设置。
- 车机语言不支持时默认简体中文。

全部差异与逐版改动见仓库 [docs/CN_OPTIMIZATIONS.md](https://github.com/serein-morii/DiPlay-CN/blob/main/docs/CN_OPTIMIZATIONS.md)。

## 目录

- [[安装与升级]]
- [[连接方式]]
- [[仪表盘导航与小屏模式]]
- [[ADB-相关功能]]
- [[常见问题]]
- [[诊断报告]]
- [[CN-与官方-DiPlay-的差异]]
- [[从源码构建]]

## 安全与法律

独立社区项目，非 Apple 认证产品（未参与 MFi），与 Apple Inc.、比亚迪无任何隶属或授权关系。软件按「现状」免费提供，使用风险自负；完整免责声明见各版本发布说明。仪表是独立 CAN 控制器，涉及仪表写入的功能均默认关闭，出现「简易模式」见 [[常见问题]]。
