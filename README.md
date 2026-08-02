<!--suppress ALL -->

# Qishui Lyric Provider - 汽水音乐歌词提供器

#### 基于 Xposed 的汽水音乐（Luna）歌词提供器

![Platform](https://img.shields.io/badge/Platform-Android-brightgreen?style=flat&logo=android)
![Min SDK](https://img.shields.io/badge/Min%20SDK-27-orange?style=flat&logo=android)
![License](https://img.shields.io/github/license/DistrictBlauw/qishui-lyric-provider?style=flat)
![Last Commit](https://img.shields.io/github/last-commit/DistrictBlauw/qishui-lyric-provider?style=flat)
![Build](https://img.shields.io/github/actions/workflow/status/DistrictBlauw/qishui-lyric-provider/android-release.yml?style=flat&logo=github)

<p align="left">
  <a href="https://github.com/DistrictBlauw/qishui-lyric-provider/releases">
    <img src="https://img.shields.io/github/v/release/DistrictBlauw/qishui-lyric-provider?style=flat&color=blue&logo=github" alt="Release">
  </a>
  <a href="https://github.com/DistrictBlauw/qishui-lyric-provider/releases">
    <img src="https://img.shields.io/github/downloads/DistrictBlauw/qishui-lyric-provider/total?style=flat&color=orange" alt="Downloads">
  </a>
</p>

---

## 📖 项目简介

本项目是 [LyricProvider](https://github.com/tomakino/LyricProvider) 的汽水音乐模块独立版本。

通过 Hook 汽水音乐（字节跳动 Luna 音乐，包名 `com.luna.music`）的 `MediaSession`，读取其本地网络缓存中的歌词数据，并向已适配 **Lyricon** 标准的歌词订阅端（如光锥音乐、BBPlayer 等）提供：

- 🎵 **动态歌词**（KRC 逐字 / LRC 逐行）
- 🌐 **翻译歌词**（自动匹配系统语言）
- ℹ️ **歌曲元数据**（标题、艺人、时长）

### 工作原理

汽水音乐在播放歌曲时会通过 [`NetCacheLoader`](qishui-music/src/main/kotlin/io/github/proify/lyricon/qishuiprovider/xposed/QiShui.kt) 将完整的 `GetTrackResponse` JSON 缓存到本地磁盘。本模块通过逆向分析定位缓存文件，直接读取并解析其中的歌词与元数据，无需额外网络请求。

**缓存路径**（基于逆向 [`NetCacheLoader.getCacheFilePath()`](qishui-music/src/main/kotlin/io/github/proify/lyricon/qishuiprovider/xposed/QiShui.kt:154)）：

```
{cacheDir 或 externalCacheDir}/NetCacheLoader/{userId}/{md5("/luna/track_v2/" + trackId)}
```

- 缓存有效期：**7 天**（604800000 ms）
- 文件名：HTTP 请求路径 + trackId 的 32 位小写 MD5
- 根目录受 `ExternalCacheDirConfig` 远程配置控制，模块同时检查两个候选目录

---

## 📥 快速安装

1. **下载**：前往 [Releases 页面](https://github.com/DistrictBlauw/qishui-lyric-provider/releases) 获取最新的 APK 安装包。
2. **激活**：安装后进入 **LSPosed 管理器**，勾选启用 **汽水音乐歌词提供器**。
3. **配置作用域**：在 LSPosed 中勾选 **汽水音乐**（`com.luna.music`）。
4. **生效**：强行停止并重新打开汽水音乐即可体验。

---

## 🎯 已适配的订阅端

以下应用已适配 Lyricon 歌词订阅标准，可直接接收本模块提供的歌词：

- [**光锥音乐**](https://coneplayer.trantor.ink/)
- **Flamingo**
- [**BBPlayer**](https://bbplayer.roitium.com/)
- **MobiMusic**
- [**Kanade**](https://github.com/rcmiku/Kanade)
- **Sollin Player**
- [**QZ Music**](https://github.com/lqtmcstudio/QZMusic)
- [**棉花音乐**](https://github.com/pure-music/PureMusic)

> 完整订阅端开发文档请参考 [Lyricon 开发文档](https://tomakino.github.io/lyricon/zh-cn/developer/subscriber/)

---

## 🏗️ 项目结构

```
qishui-lyric-provider/
├── qishui-music/              # 汽水音乐主模块（Xposed Application）
│   └── src/main/kotlin/.../xposed/
│       ├── QiShui.kt          # 核心 Hook 逻辑
│       ├── HookEntry.kt       # Xposed 入口
│       ├── MetadataCache.kt   # MediaSession 元数据缓存
│       ├── Constants.kt       # 常量定义（包名、图标）
│       └── parser/
│           ├── NetResponseCache.kt  # 缓存 JSON 结构映射
│           ├── Helper.kt            # 歌词解析辅助
│           └── KtvLyricParser.kt    # KRC 逐字歌词解析器
├── share/
│   ├── extensions-kt/         # Kotlin 通用扩展（MD5、JSON 等）
│   ├── extensions-android/    # Android 平台扩展
│   └── lrckit/                # LRC 歌词解析库
├── build.gradle.kts           # 根构建脚本
├── settings.gradle.kts        # 模块配置
└── .github/workflows/         # CI/CD 构建脚本
```

---

## 🛠️ 本地构建

### 环境要求

- **JDK** 21+
- **Android SDK**（compileSdk 37, targetSdk 37, minSdk 27）
- **Gradle** 9.3.1+（项目自带 wrapper）

### 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（需配置签名）
./gradlew assembleRelease
```

### 签名配置

Release 构建需要通过环境变量提供签名信息：

```bash
export RELEASE_STORE_FILE=/path/to/release.jks
export RELEASE_STORE_PASSWORD=your_store_password
export RELEASE_KEY_ALIAS=your_key_alias
export RELEASE_KEY_PASSWORD=your_key_password
```

---

## 📦 CI/CD

本项目使用 GitHub Actions 进行自动化构建，配置位于 [`.github/workflows/android-release.yml`](.github/workflows/android-release.yml)。

- **手动触发**：在 Actions 页面选择 build type（debug/release）手动运行
- **Tag 触发**：推送 `v*` 格式的 tag 时自动构建 release APK 并发布到 Releases

构建产物会上传为 Artifact，tag 触发时同时创建 GitHub Release。

---

## 📜 技术细节

### 逆向分析依据

本模块的缓存读取逻辑基于对汽水音乐 APK（`com.luna.music`）的逆向分析：

| 逆向类 | 作用 |
|:---|:---|
| `NetCacheLoader.getCacheFilePath()` | 缓存路径生成核心逻辑 |
| `IdCacheKeyProvider` | 缓存 key = HTTP路径 + "/" + trackId |
| `TrackApi` | `@POST("/luna/track_v2")` 定义请求路径 |
| `MD5Util.c()` | 32 位小写 MD5 哈希 |
| `ExternalCacheDirConfig` | 外部缓存目录开关（远程可配置） |
| `TrackRepo.y0()` | 网络请求与缓存策略（7天有效期） |

### 关键优化

1. **双目录搜索**：同时检查 `cacheDir` 和 `externalCacheDir`，应对 `ExternalCacheDirConfig` 远程开关
2. **元数据回退**：优先使用 MediaSession 提供的标题/艺人，缺失时从缓存 JSON 的 `track` 字段补全
3. **向后兼容**：`NetResponseCache` 新增字段均有默认值，旧版缓存 JSON 仍可正常解析

---

## 📄 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。

原始项目：[tomakino/LyricProvider](https://github.com/tomakino/LyricProvider)

---

## 🙏 鸣谢

- [tomakino](https://github.com/tomakino) - 原始 LyricProvider 项目
- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) - Xposed Hook 框架
- [kavaref](https://github.com/HighCapable/kavaref) - Kotlin 反射增强库
- [Lyricon](https://github.com/tomakino/lyricon) - 歌词提供标准
