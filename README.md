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

通过 Hook 汽水音乐（字节跳动 Luna 音乐，包名 `com.luna.music`）内部的歌词赋值与 `MediaSession`，向已适配 **Lyricon** 标准的歌词订阅端（如光锥音乐、BBPlayer 等）提供：

- 🎵 **动态歌词**（KRC 逐字 / LRC 逐行）
- 🌐 **翻译歌词**（自动匹配系统语言）
- ℹ️ **歌曲元数据**（标题、艺人、时长）
- ▶️ **播放状态**（与 MediaSession 同步）

### 工作原理

汽水音乐在拿到曲目详情后，会将歌词写入内存对象 `Track.trackLyric`（`track_v2` 响应常使用 `ServerPriorityStrategy`，**不一定落盘**）。本模块：

1. Hook `Track.setTrackLyric(TrackLyric)`，在内存中拦截官方歌词与翻译
2. Hook `MediaSession.setMetadata` / `setPlaybackState`，获取当前曲目 ID、元数据与播放状态
3. 仅在 `trackId` 与当前 `mediaId` **匹配**时绑定并推送，避免冷启动预取串词
4. 将 KRC/LRC 解析为 Lyricon `RichLyricLine`，经 Lyricon Provider 推送给订阅端

**无需额外网络请求**，歌词来自汽水官方数据路径。

```
MediaSession (mediaId / 元数据 / 播放状态)
        +
Track.setTrackLyric (KRC|LRC + 翻译)
        ↓
  内存 lyricCache（按 trackId，匹配后才别名 mediaId）
        ↓
  toRichLyric() → Song → Lyricon 订阅端
```

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
│       ├── QiShui.kt          # 核心 Hook：MediaSession + setTrackLyric + 推送
│       ├── HookEntry.kt       # Xposed / YukiHookAPI 入口
│       ├── MetadataCache.kt   # MediaSession 元数据缓存
│       ├── DebugLogger.kt     # 分级异步日志（LSPosed / logcat / 文件）
│       ├── Constants.kt       # 包名、图标等常量
│       └── parser/
│           ├── NetResponseCache.kt  # 歌词数据结构（兼容旧 JSON 样例）
│           ├── Helper.kt            # KRC/LRC 解析与翻译对齐
│           └── KtvLyricParser.kt    # KRC 逐字解析器
├── share/
│   ├── extensions-kt/         # Kotlin 通用扩展
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
- **Gradle**（项目自带 wrapper）

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

### 数据来源（当前实现）

| Hook / 组件 | 作用 |
|:---|:---|
| `Track.setTrackLyric(TrackLyric)` | 拦截内存歌词：原文、类型（krc/lrc）、trackId、多语言翻译 |
| `MediaSession.setMetadata` | 当前 `mediaId`、标题、艺人、时长 |
| `MediaSession.setPlaybackState` | 播放状态同步；歌词晚到时触发补推 |
| `LyriconFactory.createProvider` | 向 Lyricon 订阅端注册并推送 `Song` |

### 歌词绑定策略（防串词）

冷启动时汽水可能**预取多首**歌词。模块约定：

1. **只按 `trackId` 写入权威缓存**；无 `trackId` 的数据直接丢弃
2. **仅当 `trackId` 与当前 `mediaId` 匹配**时，才建立 `mediaId` 别名并推送
3. 匹配当前曲时 **强制 `updateSong()`**，覆盖此前空歌词或错误歌词
4. `findLyric(mediaId)` 支持精确键与模糊匹配（归一化、子串、长数字 id）

> 这修复了「启动后第一首歌歌词不对」：旧逻辑曾把任意预取歌词盲写到 `curMediaId`，且错误歌词推送后不再刷新。

### 解析能力

| 类型 | 解析器 | 说明 |
|:---|:---|:---|
| `krc` | `KtvLyricParser` | 逐字：`[start,duration]` + `<offset,duration,?>字` |
| `lrc` | `share/lrckit` `LrcParser` | 逐行；翻译常用 LRC |
| 翻译 | `Helper.getLangKeyForTranslations` | 按系统 Locale 匹配（含中文 Hans/Hant 回退），`findClosest(±50ms)` 对齐 |

### 调试日志

[`DebugLogger`](qishui-music/src/main/kotlin/io/github/proify/lyricon/qishuiprovider/xposed/DebugLogger.kt) 提供分级日志：

- 级别：`V / D / I / W / E`（默认最低 `DEBUG`）
- 通道：LSPosed（`XposedBridge`）+ logcat（tag `QishuiLyric`）+ 可选文件
- 文件异步写入，超过 2MB 轮转备份；候选路径：
  1. `/sdcard/qishui-lyric-debug.log`
  2. `{externalCacheDir}/qishui-lyric-debug.log`
  3. `{cacheDir}/qishui-lyric-debug.log`

排查首曲/串词时可在日志中搜索：`onLyricArrived`、`matches current`、`findLyric`、`setMetadata`。

### 逆向分析依据

| 逆向类 / 符号 | 作用 |
|:---|:---|
| `com.luna.common.arch.db.entity.Track` | `setTrackLyric` 歌词入口 |
| `com.luna.common.arch.db.entity.TrackLyric` | lyric / type / trackId / langTranslations |
| `TrackApi` `@POST("/luna/track_v2")` | 曲目详情与歌词来源 API |
| `ServerPriorityStrategy` | 响应优先内存，不一定写 `NetCacheLoader` 磁盘缓存 |

> 历史方案曾读取 `{cacheDir}/NetCacheLoader/{userId}/{md5("/luna/track_v2/"+trackId)}`。当前以内存 Hook 为准；`NetResponseCache` 仍兼容旧 JSON 结构（单测资源 `1.json` / `2.json`）。

---

## 🐛 已知问题与修复

| 问题 | 状态 | 说明 |
|:---|:---|:---|
| 启动后第一首歌歌词不对 | **已修复** | 禁止预取歌词盲写 `curMediaId`；匹配当前曲强制刷新 |
| 汽水改版导致 Hook 失效 | 依赖维护 | 类名/方法名变更时需更新 Hook 点 |

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
