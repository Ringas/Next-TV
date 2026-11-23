# Next TV

在电视上干净、好用的直播播放器。面向 Android TV 与盒子，也照顾到接了键鼠或触屏的设备。

你自己添加订阅源，应用负责把频道、节目单和播放体验整理顺。

## 适合怎样用

- 用遥控器在频道之间快速浏览、数字键切台
- 按分类看频道，收藏常用台
- 换台时尽量少黑屏：可预热相邻频道，也可保留上一画面直到新画面出来
- 有节目单时查看当前与后续节目；支持源提供的回看时，可在时间轴上选播
- 多线路自动尝试，播不动时换线、恢复，而不是卡死在错误页
- 深色 / 浅色界面，列表或网格，可按喜好关掉多余动效

不内置任何频道或运营内容。装好后在设置里添加你的 M3U 订阅（以及可选的 XMLTV 节目单）即可使用。

## 能做什么

| 能力 | 说明 |
|------|------|
| 订阅 | 远程 M3U 或本地文件，多源合并，失败时保留已有列表 |
| 节目单 | XMLTV；可跟订阅走，也可单独指定 |
| 播放 | HLS 等常见直播流（Media3）；多线路与故障恢复 |
| 预热 | 关闭 / 下一台 / 上一台 / 双向 |
| 回看 | 在源与节目单支持时可用 |
| 输入 | 遥控器、键盘、鼠标、触屏 |
| 其它 | 台标缓存、局域网扫码/网页辅助填写订阅地址、MP2 软件解码补充 |

系统要求：Android 6.0（API 23）及以上，更推荐实际的 Android TV / 盒子环境。

## 使用提示

1. 安装后打开 **设置 → 订阅与节目单**，添加订阅地址或导入本地列表。  
2. 需要节目信息时配置节目单（订阅自带或自定义 XMLTV）。  
3. 在 **播放**、**频道与外观** 里按设备性能调节预热、预览和动效。  
4. 部分源若要求特定 UA / Referer，在 **高级 → 网络请求设置** 中填写。

应用不收集你的订阅内容作为产品数据；源的可用性与版权由提供方与使用者自行负责。

---

## 开发者

| 项 | 值 |
|----|-----|
| 应用 ID | `com.nexttv` |
| 版本 | 1.4.0（versionCode 100） |
| SDK | min 23 / target & compile 35 |
| 技术 | Kotlin、Jetpack Compose for TV、AndroidX Media3 |

### 构建

JDK 17 + Android SDK 35。配置 `ANDROID_HOME` 或 `local.properties` 中的 `sdk.dir` 后：

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:testReleaseUnitTest
```

### 签名

正式签名使用本地 PKCS#12（勿提交密钥）：

1. 将密钥库放到 `.signing/`（参考 `signing.properties.example`）  
2. 编写 `.signing/signing.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）  

存在该配置时，Release（及可选 Debug）会按此证书签名。`.signing/`、`*.p12`、`*.jks` 已在 `.gitignore` 中忽略。

### 测试与优化

- `app/src/test`：单元测试  
- `app/src/androidTest`：设备专项（部分需自备测试源）  
- `baselineprofile`：Release 启动路径相关配置  

### 许可

以 **GNU GPL v3 only（GPL-3.0-only）** 发布，见 [LICENSE](LICENSE)。

主要依赖（版本以 Gradle 为准）：AndroidX、Kotlin、coroutines、ZXing 为 Apache-2.0；JLayer 为 LGPL-2.1；desugar_jdk_libs 为 GPL-2.0 with Classpath Exception。第三方组件仍遵循其原有许可。
