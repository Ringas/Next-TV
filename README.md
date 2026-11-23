# Next TV

面向 Android TV 的直播播放器，同时支持遥控器、键盘、鼠标与触屏。

| 项目 | 说明 |
|------|------|
| 应用 ID | `com.nexttv` |
| 版本 | 1.3.51（versionCode 98） |
| SDK | min 23 / target & compile 35 |
| 技术 | Kotlin、Jetpack Compose for TV、AndroidX Media3 |

## 功能

- M3U 订阅（远程 URL / 本地文件），多源合并与缓存
- XMLTV 节目单，多线路与故障恢复
- 频道预热、换台保画、可选回看
- MP2 软件解码补充（JLayer）
- 不内置任何频道或订阅内容，安装后自行添加源

## 构建

需要 **JDK 17** 与 **Android SDK 35**。配置 `ANDROID_HOME` 或在本机创建 `local.properties`（`sdk.dir=...`）后：

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:testReleaseUnitTest
```

未配置签名时，Debug 使用调试证书，Release 输出未签名 APK。正式签名可在项目根目录创建 `.signing/signing.properties`（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`，PKCS#12）。

## 测试

- `app/src/test`：JVM 单元测试  
- `app/src/androidTest`：设备专项与性能相关测试（部分依赖自备测试源）  
- `baselineprofile`：Release 启动路径优化配置  

## 开源许可

本项目以 **GNU GPL v3 only（GPL-3.0-only）** 发布，完整文本见 [LICENSE](LICENSE)。

运行时主要依赖及其许可（版本以 Gradle 配置为准）：

- AndroidX（Compose / TV Material / Media3 / Lifecycle 等）、Kotlin、kotlinx.coroutines、ZXing：**Apache-2.0**
- JLayer（MP2 解码补充）：**LGPL-2.1**
- desugar_jdk_libs：**GPL-2.0 with Classpath Exception**

第三方组件版权归其原作者，各自许可条款仍然有效，不被本项目的 GPL 声明取代。

本应用不提供电视频道、订阅服务或节目内容；用户添加的源及其权利由用户自行负责。
