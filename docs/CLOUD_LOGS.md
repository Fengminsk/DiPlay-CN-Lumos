# DiPlay 运行日志云存储

在「设置 → 诊断」手动输入 COS 桶名、Endpoint、SecretId 和 SecretKey，或导入本机 JSON。
导入后默认关闭上传，需要明确打开「上传运行日志到 COS」。JSON 使用下列结构，**占位符不是密钥**：

```json
{
  "bucket": "example-1234567890",
  "endpoint": "cos.ap-guangzhou.myqcloud.com",
  "secretId": "<enter-on-device>",
  "secretKey": "<enter-on-device>"
}
```

建议为车机创建只允许 `Logs/com.shihab.diplay.cn/*` 执行 `cos:PutObject` 的独立凭据。
配置保存在应用私有的 `noBackupFilesDir`，仓库与 APK 不包含实际凭据；设置界面不回显已保存的密钥。
请勿把真实配置 JSON 放进本仓库或附在诊断报告中。

启用后，进程存活期间约每 30 秒检查一次 DiPlay 的私有会话日志，只上传二次脱敏后的
gzip 文件到 `Logs/<应用包名>/YYYY-MM-DD/<时间戳>-<随机 ID>-<来源文件名>.log.gz`。
日志文件先写入应用私有待传目录，COS 返回 HTTP 200 后才删除；网络失败则保留并重试。
待传目录上限 128 MiB，到达上限时暂停新快照，已排队文件不丢弃。
停用开关会停止上传和制作新快照，待传文件仍保留，以便再次启用后续传。

这里仅上传 DiPlay 的应用会话日志，不读取系统 logcat 或 CarsBridge 私有数据。
已脱敏仍不意味着日志完全匿名；开启上传前应确认目标桶由自己控制。
