# Next TV

Next TV 是面向 Android TV 的直播播放器，也支持遥控器、键盘、鼠标和触屏。应用 ID 为 `com.nexttv`，当前源码版本为 **1.3.51（98）**。构建最低 API 为 23，compileSdk 和 targetSdk 为 35。

应用使用 Kotlin、Jetpack Compose for TV 和 AndroidX Media3。支持 M3U 订阅、XMLTV 节目单、多线路及故障恢复、双向预热、可回放节目和 JLayer MP2 软件解码补充。安装后自行添加订阅。

## 构建

使用 JDK 17 和 Android SDK 35。通过 `ANDROID_HOME` 或本机 `local.properties` 配置 SDK 后，从项目目录运行：

```sh
./gradlew :app:assembleDebug :app:testReleaseUnitTest :app:assembleRelease
```

未配置正式签名时，Debug 使用标准调试证书，Release 输出无签名 APK。配置正式包签名时，在本机创建 `.signing/signing.properties`，填写 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`，并使用 PKCS#12 密钥库。覆盖更新已发布的应用需要相同证书。

## 验证

`app/src/test` 为 JVM 单元测试。`app/src/androidTest` 包含设备专项和性能诊断。依赖真实订阅的设备测试需要有可用的测试频道及节目单。对外源码包中的局域网地址已经替换为文档示例地址，执行这些专项前需要配置自己的测试源。播放器运行不依赖这些测试地址。

`baselineprofile` 与 `app/src/release/generated/baselineProfiles` 用于 Release 启动和常用路径优化。`tools/audit_unused_resources.py` 可检查项目资源的静态引用。

## 来源与许可

本项目按 GPL-3.0-only 分发。原始来源与第三方依赖说明见 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)，完整条款见 [LICENSE](LICENSE)。
