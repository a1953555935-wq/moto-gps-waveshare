# MOTO GPS Android（开发中）

这是 [Glimpse / MOTO GPS 上游项目](https://github.com/mx3353672833-debug/moto-gps-waveshare) 的社区 Android 移植起点。当前仅实现原生 Android 工程、权限请求和按现有 BLE 服务 UUID 发现附近圆屏。**尚未实现 GATT 连接、配对、协议握手、导航或后台运行，不能用于骑行。**

## 构建

使用 Android Studio 打开 `platforms/android/`，安装 Android SDK 36 和 JDK 17，完成 Gradle 同步后运行 `app` 到支持 BLE 的 Android 8.0 及以上真机。也可在该目录执行 `gradlew.bat :app:assembleDebug`。工程使用 AGP 8.13.2、Gradle 8.13、Kotlin 2.2.20 和 Compose BOM 2026.09.00。当前环境没有 Android SDK/JDK，构建与真机运行尚未验证。

本阶段会请求蓝牙扫描、连接与精确位置权限；扫描结果只在前台显示。BLE 服务 UUID 来自 `shared/ble_protocol`。后续应通过 JNI 复用共享协议，完成加密订阅、四步握手、分片、ACK、导航状态和网关接入。高德 Web 服务 Key 留在服务端。

保留上游 `LICENSE.md`、`NOTICE` 和第三方许可说明。真实设备型号、固件版本和测试结果将在实测后记录。
