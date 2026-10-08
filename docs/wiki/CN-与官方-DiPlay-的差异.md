# CN 与官方 DiPlay 的差异

基线：官方 `v0.2.14`（上游主干另合入 #422 应用外观等，CN 下一次同步时带上）。逐版明细见 [CN_OPTIMIZATIONS.md](https://github.com/serein-morii/DiPlay-CN/blob/main/docs/CN_OPTIMIZATIONS.md)。

## CN 专属功能

| 功能 | 位置 |
|---|---|
| 自定义仪表转向卡（1% 滑条、透明度、昼夜、行程条、断线保留/终点清卡） | 设置 → BYD 导航 |
| 小屏导航三模式（关/开/自动，ADB 优先） | 设置 → BYD 导航 |
| 应用内更新 + 四通道（Gitee/GitHub/两个镜像） | 设置 → 关于 |
| 开机自启 ADB 修复 | 设置 → 自动连接 |
| CarPlay 期间延时暂停车机蓝牙（通话走中控喇叭） | 设置 → 位置 → 高级车辆数据 |
| 已选 iPhone 蓝牙重连自动连接（默认关） | 设置 → 自动连接 |
| 点图标回 CarPlay 开关；下滑菜单顶部「打开完整设置」 | 设置 → 车辆 / CarPlay 下滑菜单 |
| 应用名称/图标预置（DiPlayCN 等无空格名） | 设置 |
| 简体中文回退、固定签名、Gitee 发布校验 | 底层 |

## CN 从官方未合 PR 拿来的（0.2.14-cn.4）

- 旋转方形画布报真实物理宽度（官方 #404）
- 音乐欠载后按半阈值重新预缓冲（官方 #401）
- Wi-Fi Direct 自动 · 5 GHz / 2.4 GHz（官方 #432 的频段部分；同 PR 的「CarPlay 音频走车机蓝牙」因与 CN 暂停蓝牙冲突**未合**）

## 官方有、CN 同步携带

0.2.14 全部：设置分类搜索、界面大小、自动连接方式、定时昼夜、USB/蓝牙/热点恢复、平滑视频（实验）、方向盘 Siri、回声消除与清晰人声（实验、默认关）、DiLink 3 仪表尺寸识别等。

## 已提交给官方的 PR

来自 CN 的分支：[serein-morii/DiPlay](https://github.com/serein-morii/DiPlay) 的 `feat/cluster-small-window-navi`、`feat/iphone-bluetooth-wake-connect`、`feat/1pct-cluster-sliders`、`feat/boot-start-adb-repair`，以及已开放的 [#307 暂停蓝牙](https://github.com/shihabal3amri/DiPlay/pull/307)。
