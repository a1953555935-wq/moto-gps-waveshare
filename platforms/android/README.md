# MOTO GPS Android（开发中）

这是 [Glimpse / MOTO GPS 上游项目](https://github.com/mx3353672833-debug/moto-gps-waveshare) 的社区 Android 移植起点。现有代码可扫描圆屏、由用户选择设备、发现 GATT 服务、订阅通知，并通过 JNI 使用 `shared/ble_protocol` 完成四步握手与心跳。**尚未接入导航数据、断线自动重连或后台服务，不能用于骑行。**

## 构建

使用 Android Studio 打开 `platforms/android/`，安装 Android SDK 36 和 JDK 17，完成 Gradle 同步后运行 `app` 到支持 BLE 的 Android 8.0 及以上真机。也可在该目录执行 `gradlew.bat :app:assembleDebug`。工程使用 AGP 8.13.2、Gradle 8.13、Kotlin 2.2.20 和 Compose BOM 2025.12.00。当前本机没有 Android SDK/JDK，构建由 GitHub Actions 检查，真机运行尚未验证。

Android 12 及以上扫描时请求附近设备权限；旧系统需要精确位置权限。导航定位权限在后续真实导航阶段单独请求。扫描仅持续十秒；进入后台超过 30 秒会关闭当前连接，留出完成系统配对提示的时间。BLE 服务 UUID 来自 `shared/ble_protocol`，协议编码、CRC、分片和重组复用共享 C++ 实现。通知订阅使用固件要求的加密 CCCD 写入；首次配对是否能在目标手机上顺利触发，仍需真机验证。

后续需要处理应用 ACK、设备命令、导航状态、重连与前台服务，再接入网关。高德 Web 服务 Key 留在服务端。

保留上游 `LICENSE.md`、`NOTICE` 和第三方许可说明。真实设备型号、固件版本和测试结果将在实测后记录。
