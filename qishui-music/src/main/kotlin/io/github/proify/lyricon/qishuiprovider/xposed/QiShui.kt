package io.github.proify.lyricon.qishuiprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.extensions.json
import io.github.proify.extensions.md5
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.qishuiprovider.xposed.parser.NetResponseCache
import io.github.proify.lyricon.qishuiprovider.xposed.parser.toRichLyric
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.File

object QiShui : YukiBaseHooker() {

    private const val TAG = "QiShui"
    private var provider: LyriconProvider? = null

    private var curMediaId: String? = null
    private var lastSong: Song? = null

    override fun onHook() {
        YLog.info(tag = TAG, msg = "$packageName/$processName")
        DebugLogger.log(TAG, "onHook() called, packageName=$packageName, processName=$processName")

        onAppLifecycle {
            onCreate {
                hook()
            }
        }
    }

    private var hooked = false
    private fun hook() {
        if (hooked) {
            YLog.info(tag = TAG, msg = "何意味")
            DebugLogger.log(TAG, "hook() already called, skipping")
            return
        }
        hooked = true
        DebugLogger.log(TAG, "hook() first invocation, initializing")

        initProvider()
        hookMediaSession()
        hookNetCacheLoader()
    }

    private fun initProvider() {
        val context = appContext ?: run {
            DebugLogger.log(TAG, "initProvider() FAILED: appContext is null")
            return
        }
        // 初始化文件日志所需的目录
        DebugLogger.appCacheDir = context.cacheDir
        DebugLogger.appExternalCacheDir = runCatching { context.externalCacheDir }.getOrNull()
        DebugLogger.log(TAG, "initProvider() appContext=${context.packageName}, cacheDir=${context.cacheDir}, externalCacheDir=${context.externalCacheDir}")

        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON),
            processName = processName
        ).apply {
            player.setDisplayTranslation(true)
            register()
        }
        YLog.debug(tag = TAG, msg = "provider registered, provider=${provider?.providerInfo}")
        DebugLogger.log(TAG, "initProvider() done, provider registered=${provider != null}, logPath=${DebugLogger.currentLogPath()}")
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = args[0] as? PlaybackState
                        provider?.player?.setPlaybackState(state)
                        updateSongIfNeed()
                    }
                }

                firstMethod {
                    name = "setMetadata"
                    parameters("android.media.MediaMetadata")
                }.hook {
                    after {
                        val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                        val id = mediaMetadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

                        DebugLogger.log(TAG, "setMetadata received, mediaId=$id, title=${mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE)}, artist=${mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}, duration=${mediaMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION)}")

                        if (curMediaId == id) {
                            DebugLogger.log(TAG, "setMetadata: mediaId unchanged ($id), skipping")
                            return@after
                        }

                        curMediaId = id
                        MetadataCache.save(mediaMetadata)
                        DebugLogger.log(TAG, "setMetadata: new mediaId=$id, calling updateSong()")
                        updateSong()
                    }
                }
            }
    }

    /**
     * Hook MD5Util.c(String) 来捕获所有缓存文件名的原始 key 值。
     *
     * MD5Util.c(rawKey) 是 NetCacheLoader 计算缓存文件名的唯一入口。
     * 通过 hook 这个方法，我们可以看到所有写入/读取缓存时的 rawKey 和对应的 md5 文件名，
     * 从而确定歌词缓存的真实 key 格式。
     */
    private fun hookNetCacheLoader() {
        runCatching {
            "com.luna.common.secure.MD5Util".toClass()
                .resolve()
                .apply {
                    // c(String) -> String : MD5 of string bytes
                    firstMethod {
                        name = "c"
                        parameters(String::class.java)
                    }.hook {
                        after {
                            val input = args.getOrNull(0) as? String
                            val output = result as? String
                            // 只记录看起来像缓存 key 的（包含 "/" 的路径）
                            if (input != null && (input.contains("/") || input.contains("luna") || input.contains("track"))) {
                                DebugLogger.log(TAG, "[MD5Util.c] input(rawKey)=$input, output(md5)=$output")
                                // 将 rawKey -> md5 映射存入缓存
                                if (input.isNotEmpty() && output != null) {
                                    rawKeyToMd5Cache[input] = output
                                }
                            }
                        }
                    }
                }
            DebugLogger.log(TAG, "hookNetCacheLoader() hooks installed successfully")
        }.onFailure {
            DebugLogger.log(TAG, "hookNetCacheLoader() FAILED to install hooks", it)
        }
    }

    /** rawKey -> md5(rawKey) 的映射，用于调试 */
    private val rawKeyToMd5Cache = mutableMapOf<String, String>()
    private fun updateSongIfNeed() {
        if (curMediaId.isNullOrBlank()) return
        val lastSong = this.lastSong
        if (lastSong?.lyrics.isNullOrEmpty()) {
            DebugLogger.log(TAG, "updateSongIfNeed: lyrics empty, re-triggering updateSong for mediaId=$curMediaId")
            updateSong()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun updateSong() {
        val id = curMediaId ?: run {
            DebugLogger.log(TAG, "updateSong: curMediaId is null, aborting")
            return
        }
        DebugLogger.log(TAG, "updateSong: START, mediaId=$id")

        val cache = runCatching {
            val file = getNetLyricCacheFile(id)
            DebugLogger.log(TAG, "updateSong: getNetLyricCacheFile result = ${file?.absolutePath ?: "null"}")
            if (file != null && file.exists()) {
                DebugLogger.log(TAG, "updateSong: cache file found, size=${file.length()} bytes, reading content")
                // 先读取原始内容用于诊断（判断是否加密/是否为合法 JSON）
                val rawContent = runCatching { file.readText() }.getOrNull()
                val preview = rawContent?.take(200)
                DebugLogger.log(TAG, "updateSong: raw file preview (first 200 chars): ${preview?.replace("\n", "\\n")}")
                // 简单判断是否是 JSON（以 { 开头），若不是则可能已被加密
                val looksLikeJson = rawContent?.trimStart()?.startsWith("{") == true
                DebugLogger.log(TAG, "updateSong: content looksLikeJson=$looksLikeJson (if false, cache may be encrypted)")

                file.inputStream().use {
                    json.decodeFromStream<NetResponseCache>(it)
                }
            } else {
                DebugLogger.log(TAG, "updateSong: cache file NOT found for mediaId=$id")
                null
            }
        }.onFailure {
            YLog.error(tag = TAG, msg = "cache load failed, mediaId=$id, error=$it")
            DebugLogger.log(TAG, "updateSong: cache load FAILED (decode error), mediaId=$id", it)
        }.getOrNull()

        if (cache == null) {
            DebugLogger.log(TAG, "updateSong: cache is null, falling back to MetadataCache only")
            val metadata = MetadataCache.get(id)
            DebugLogger.log(TAG, "updateSong: MetadataCache.get($id) = title=${metadata?.title}, artist=${metadata?.artist}, duration=${metadata?.duration}")
            setSong(Song(name = metadata?.title, artist = metadata?.artist))
            return
        }

        DebugLogger.log(TAG, "updateSong: cache decoded OK, lyric.type=${cache.lyric?.type}, lyric.content.length=${cache.lyric?.content?.length}, lyric.lang_translations.keys=${cache.lyric?.lang_translations?.keys}, track.name=${cache.track?.name}, track.artists=${cache.track?.artists?.map { it.name }}, track.duration=${cache.track?.duration}")

        // 缓存 JSON 中自带 track 元数据，优先用于补全 MediaMetadata 缺失的标题/艺人
        val metadata = MetadataCache.get(id)
        val song = cache.buildSong(id, metadata)
        DebugLogger.log(TAG, "updateSong: buildSong done, song.name=${song.name}, song.artist=${song.artist}, song.duration=${song.duration}, song.lyrics.size=${song.lyrics?.size}")
        setSong(song)
    }

    private fun setSong(song: Song) {
        if (song == lastSong) {
            DebugLogger.log(TAG, "setSong: song unchanged (same as lastSong), skipping setSong to provider")
            return
        }
        DebugLogger.log(TAG, "setSong: setting song to provider, name=${song.name}, lyrics.size=${song.lyrics?.size}")
        provider?.player?.setSong(song)
        lastSong = song
    }

    fun NetResponseCache.buildSong(id: String, metadata: Metadata?): Song {
        // 优先用 MediaSession 元数据，缺失时回退到缓存 JSON 中的 track 字段
        val trackName = metadata?.title?.takeIf { it.isNotBlank() }
            ?: track?.name.orEmpty()
        val trackArtist = metadata?.artist?.takeIf { it.isNotBlank() }
            ?: track?.artistsText.orEmpty()
        val trackDuration = metadata?.duration?.takeIf { it != 0L && it != Long.MAX_VALUE }
            ?: track?.duration ?: 0L
        DebugLogger.log(TAG, "buildSong: id=$id, trackName=$trackName (fromMeta=${metadata?.title}, fromTrack=${track?.name}), trackArtist=$trackArtist (fromMeta=${metadata?.artist}, fromTrack=${track?.artistsText}), trackDuration=$trackDuration")
        val lyrics = toRichLyric()
        DebugLogger.log(TAG, "buildSong: toRichLyric() returned ${lyrics.size} lines")
        return Song(
            id = id,
            name = trackName,
            artist = trackArtist,
            duration = trackDuration,
            lyrics = lyrics
        )
    }

    /**
     * 候选的 NetCacheLoader 根目录列表。
     *
     * 逆向分析（NetCacheLoader.getCacheFilePath）显示缓存根目录取值优先级：
     *  1. useFilesDir=true  -> getFilesDir()         （本场景 useFilesDir=false，不适用）
     *  2. ExternalCacheDirConfig=true -> getExternalCacheDir()
     *  3. 默认 -> getCacheDir()
     * ExternalCacheDirConfig 默认 false，但为远程可配置项（DeviceConfigManager），
     * 运行时可能被切换为 true，因此此处同时检查两个候选目录。
     */
    private val netCacheLoaderDirs: List<File> by lazy {
        val ctx = appContext ?: run {
            DebugLogger.log(TAG, "netCacheLoaderDirs: appContext is null, returning empty list")
            return@lazy emptyList()
        }
        val dirs = buildList {
            ctx.cacheDir.resolve("NetCacheLoader").let { add(it) }
            runCatching { ctx.externalCacheDir?.resolve("NetCacheLoader") }
                .getOrNull()?.let { add(it) }
        }.distinct()
        DebugLogger.log(TAG, "netCacheLoaderDirs resolved: ${dirs.map { it.absolutePath }}, cacheDir=${ctx.cacheDir.absolutePath}, externalCacheDir=${ctx.externalCacheDir}")
        dirs
    }

    fun getNetLyricCacheFile(id: String): File? {
        val fileName = calculateLyricCacheFileName(id)
        DebugLogger.log(TAG, "getNetLyricCacheFile: START, id=$id, expected fileName(md5)=$fileName, rawKey=\"/luna/track_v2/$id\"")

        return runCatching {
            var targetFile: File? = null
            DebugLogger.log(TAG, "getNetLyricCacheFile: scanning ${netCacheLoaderDirs.size} candidate dirs")
            for (root in netCacheLoaderDirs) {
                DebugLogger.log(TAG, "getNetLyricCacheFile: checking root=${root.absolutePath}, isDirectory=${root.isDirectory}, exists=${root.exists()}")
                if (!root.isDirectory) continue
                // 路径结构: {root}/{mUid}/{fileName}（subDir=null 时无额外子目录层）
                val uidDirs = root.listFiles()?.toList() ?: emptyList()
                DebugLogger.log(TAG, "getNetLyricCacheFile: root has ${uidDirs.size} entries: ${uidDirs.map { "${it.name}(dir=${it.isDirectory})" }}")
                uidDirs.forEach { uidDir ->
                    if (targetFile != null || !uidDir.isDirectory) return@forEach
                    val filesInUid = uidDir.listFiles()?.toList() ?: emptyList()
                    DebugLogger.log(TAG, "getNetLyricCacheFile: uidDir=${uidDir.name}, contains ${filesInUid.size} files: ${filesInUid.map { it.name }}")
                    filesInUid.forEach { file ->
                        if (file.isFile && file.name == fileName) {
                            targetFile = file
                            DebugLogger.log(TAG, "getNetLyricCacheFile: MATCH FOUND! file=${file.absolutePath}, size=${file.length()}")
                            return@forEach
                        }
                    }
                }
                if (targetFile != null) break
            }
            if (targetFile == null) {
                DebugLogger.log(TAG, "getNetLyricCacheFile: NO MATCH found across all dirs for fileName=$fileName")
            }
            targetFile
        }.onFailure {
            YLog.error(tag = TAG, msg = "getNetLyricCacheFile failed, mediaId=$id, error=$it")
            DebugLogger.log(TAG, "getNetLyricCacheFile: EXCEPTION", it)
        }.getOrNull()
    }

    /**
     * 计算歌词缓存文件名。
     *
     * 逆向分析结论（NetCacheLoader + IdCacheKeyProvider）：
     *  - rawKey = {HTTP 请求路径} + '/' + {trackId}
     *  - HTTP 路径来自 TrackApi @POST("/luna/track_v2")
     *  - 文件名 = MD5Util.c(rawKey)，即 rawKey 字节的 32 位小写 MD5
     */
    fun calculateLyricCacheFileName(id: String): String =
        "/luna/track_v2/$id".md5()
}