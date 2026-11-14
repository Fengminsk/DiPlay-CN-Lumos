# Lumos 上游同步策略（强制）

本文件是 `Lumos` 产品线的强制规则，适用于所有进入本仓库的 agent 和开发者。
This file is mandatory for every agent and developer working in this repository.

## 结论

`Lumos` 是产品主线。`upstream-cn`（serein-morii/DiPlay-CN）只是**零件库**：
只取功能升级和 bug 修复，永远不要整包合并，永远不要用上游 UI 覆盖 Lumos。

- 产品分支：`Lumos`（GitHub 默认分支）
- 上游参考：`upstream-cn/main`（只 fetch，不 merge / 不 rebase / 不 fast-forward）
- 版本线：`0.2.15-cn.x-lumos.y`，不跟随上游版本号

## 允许做的事

1. `git fetch upstream-cn`，只读查看上游提交。
2. 按**单个提交 / 单个 PR** 评估，只挑明确的功能升级或 bug 修复。
3. 用 `git cherry-pick <commit>` 或手工 patch 移植；冲突时以 Lumos 结构为准改写。
4. 移植后补测试、构建、在平板验证，再考虑车机。
5. 在下面的「移植记录」里登记上游提交号、改动内容和验证结果。

## 禁止做的事

1. `git merge upstream-cn/main`、`git rebase upstream-cn/main`、整包覆盖。
2. 用上游版本覆盖以下文件/目录：
   - `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`（UI 与设置主体）
   - Lumos 首页 / Compose 主题 / 设置布局相关文件
   - `icoca/` 整个 ICCOA 模块
   - `mobile/build.gradle.kts` 中的版本号、签名、COS 烘焙、认证资产逻辑
3. 把上游的大版本 UI 重构、设置信息架构调整当成“升级”直接引入。
4. 用上游的 `applicationId`、签名、版本号或认证路径替换 Lumos 的。

## 标准同步流程

```sh
# 1. 只读拉取上游
git fetch upstream-cn

# 2. 看这次上游有什么
git log --oneline Lumos..upstream-cn/main

# 3. 逐条评估；只对选中的提交做移植
git show <commit> --stat
git cherry-pick <commit>          # 或手工 patch

# 4. 构建与测试
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:assembleRelease

# 5. 先装平板验证，再装车机
```

## 版本与交付

- 每次移植后递增 `versionCode`，`versionName` 保持 `0.2.15-cn.x-lumos.y` 形式。
- 默认分支、发布 APK、车机/平板安装包都来自 `Lumos`，不来自 `upstream-cn`。
- COS 配置和两个认证文件（`offline-mfi/identity.pk8`、`offline-mfi/certificate.p7b`）
  属于本地私有资产，只通过 `.local-cos/`、`.local-mfi/` 或环境变量提供，禁止提交进 Git。

## 移植记录

| 日期 | 上游提交 | 内容 | 结果 |
| --- | --- | --- | --- |
| 2026-11-14 | `df63a2d6` | COS 烘焙配置自动启用 + 上传状态日志 | 已移植 |
| 2026-11-14 | `65e0b8b3` / `efd1f39a` | glass home 开关 + 2 行绘制修正 | 已移植（界面本就在 Lumos，补开关与修正） |
| 2026-11-14 | `2c9b08d6` | ECARX 氛围灯包 | 旧 Lumos 已逐字节包含，无需改动 |
| 2026-11-14 | `7b6f4585` | 无线热点 IPv4/IPv6 地址族回退 | 已移植 |
| 2026-11-14 | `1bfd23a9` | Android 8/9 TCP 存活参数 | 已移植 |
| 2026-11-14 | `aa714af9` | 设置菜单/显示变化引发的无线重连 | Lumos 已有等价修复（`c876e232`） |
| 2026-11-14 | `a83284c5` / `411f0b60` | AirPlay RTSP/iAP 与 plist 安全边界 | Lumos 已有等价代码 |
