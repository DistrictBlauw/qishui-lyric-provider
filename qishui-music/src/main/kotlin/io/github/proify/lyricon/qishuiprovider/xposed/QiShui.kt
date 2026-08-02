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
            return
        }
        hooked = true

        initProvider()
        hookMediaSession()
    }

    private fun initProvider() {
        val context = appContext ?: return
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

                        if (curMediaId == id) return@after

                        curMediaId = id
                        MetadataCache.save(mediaMetadata)
                        updateSong()
                    }
                }
            }
    }

    private fun updateSongIfNeed() {
        if (curMediaId.isNullOrBlank()) return
        val lastSong = this.lastSong
        if (lastSong?.lyrics.isNullOrEmpty()) updateSong()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun updateSong() {
        val id = curMediaId ?: return

        val cache = runCatching {
            val file = getNetLyricCacheFile(id)
            if (file != null && file.exists()) {
                file.inputStream().use {
                    json.decodeFromStream<NetResponseCache>(it)
                }
            } else null
        }.onFailure {
            YLog.error(tag = TAG, msg = "cache load failed, mediaId=$id, error=$it")
        }.getOrNull()

        if (cache == null) {
            val metadata = MetadataCache.get(id)
            setSong(Song(name = metadata?.title, artist = metadata?.artist))
            return
        }

        // 缓存 JSON 中自带 track 元数据，优先用于补全 MediaMetadata 缺失的标题/艺人
        val metadata = MetadataCache.get(id)
        val song = cache.buildSong(id, metadata)
        setSong(song)
    }

    private fun setSong(song: Song) {
        if (song == lastSong) return
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
        return Song(
            id = id,
            name = trackName,
            artist = trackArtist,
            duration = trackDuration,
            lyrics = toRichLyric()
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
        val ctx = appContext ?: return@lazy emptyList()
        buildList {
            ctx.cacheDir.resolve("NetCacheLoader").let { add(it) }
            runCatching { ctx.externalCacheDir?.resolve("NetCacheLoader") }
                .getOrNull()?.let { add(it) }
        }.distinct()
    }

    fun getNetLyricCacheFile(id: String): File? {
        val fileName = calculateLyricCacheFileName(id)

        return runCatching {
            var targetFile: File? = null
            for (root in netCacheLoaderDirs) {
                if (!root.isDirectory) continue
                // 路径结构: {root}/{mUid}/{fileName}（subDir=null 时无额外子目录层）
                root.listFiles()?.forEach { uidDir ->
                    if (targetFile != null || !uidDir.isDirectory) return@forEach
                    uidDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name == fileName) {
                            targetFile = file
                            return@forEach
                        }
                    }
                }
                if (targetFile != null) break
            }
            targetFile
        }.onFailure {
            YLog.error(tag = TAG, msg = "getNetLyricCacheFile failed, mediaId=$id, error=$it")
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