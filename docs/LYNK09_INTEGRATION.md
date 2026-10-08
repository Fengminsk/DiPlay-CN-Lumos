# 领克 09 第一阶段适配

本分支在 `main` 的 CarPlay 核心上增加领克 09 的车机集成。车辆接口依据本机
`project/cars-bridge` 的已实现代码和实车研究；DiPlay 不复制其 ECARX/Geely SDK
会话，也不使用比亚迪的 `autoservice` 地址读取领克数据。

## 车速和挡位

在 DiPlay 的「设置 → 高级 → 车辆」开启「领克 09 车速与挡位」，并在「导航」
开启「向 iPhone 报告位置」。下次连接 CarPlay 后，DiPlay 通过同车机上的
CarsBridge `HvacCommandReceiver` 的有序广播调用只读 `read_instrument_status`。
CarsBridge 复用它的进程内 `HvacController.shared()`，返回挡位和车速；DiPlay
每次查询完成后等待一秒再发下一次，不并发查询。CarPlay 控制线程只取内存
中的有效样本，绝不等待车辆服务。

只有 CarsBridge 已安装且用户打开开关时，DiPlay 才向 iPhone 声明车速能力。
查询失败、挡位未知、速度缺失或超出 0–300 km/h 时不发送样本；挡位变化时
清除旧挡位下尚未发送的样本。此阶段不发送电量和续航：CarsBridge 对相关
字段仍有待实车核实的单位、有效性和覆盖率问题。

## 方向盘和音频

方向盘 Siri 键仍采用 DiPlay 的按键学习。现在非 BYD 车机也能看到可学习的
CarPlay 摇杆入口；是否能收到领克 09 的物理按键，须在车机上逐键确认。
播放、暂停、上一首和下一首通过现有 Android MediaSession 转给 iPhone。

CarPlay 音频继续使用现有的媒体、导航与通话路由及音频焦点设置。CarsBridge
的 `usage=22` 测试针对**车外扬声器**，不能作为车内 CarPlay 音频路由依据，
因此未强制启用该通道。DiPlay 的诊断报告已有实际 AudioTrack route 信息，
应以领克车机上的音乐、导航播报、Siri、通话测试结果决定是否需要路由补丁。

## 实车验收

1. 确认 CarsBridge 和 DiPlay 装在同一车机，启动 CarsBridge 常驻服务。
2. 用方向盘按键学习 Siri/摇杆；验证短按、长按和未分配按键仍能执行原车动作。
3. 连接 CarPlay，分别测试音乐、导航播报、Siri 和通话；保存 DiPlay 诊断报告中的音频路由与焦点日志。
4. 开启位置和领克 09 车速，观察 P/R/N/D、行驶车速、停车以及短暂车辆服务不可用时的样本；核对 iPhone 的定位行为。
5. 监控 CarsBridge 日志，确认没有新增 ECarXCar 会话注册风暴或影响原车操作。

源码与单测只能验证消息解析和集成路径；按键到达、实际扬声器、车速与挡位
对 iPhone 的效果需上述实车验收。

DiPlay 的运行日志云端上传参见 [CLOUD_LOGS.md](CLOUD_LOGS.md)。领克分支不内置
COS 密钥；桶配置只能在车机端输入或导入，路径使用 `Logs/<应用包名>/`。
