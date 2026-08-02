package io.github.proify.lyricon.qishuiprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.qishuiprovider.xposed.parser.NetResponseCache
import io.github.proify.lyricon.qishuiprovider.xposed.parser.toRichLyric

object QiShui : YukiBaseHooker() {

    private const val TAG = "QiShui"
    private var provider: LyriconProvider? = null

    private var curMediaId: String? = null
    private var lastSong: Song? = null

    /**
     * 内存中的歌词缓存：trackId -> NetResponseCache（含歌词内容）
     *
     * 通过 hook Track.setTrackLyric() 拦截到的歌词数据存储在此，
     * 当 MediaSession 切歌时根据 mediaId 查找对应歌词。
     */
    private val lyricCache = mutableMapOf<String, NetResponseCache>()

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
        hookTrackLyric()
    }

    private fun initProvider() {
        val context = appContext ?: run {
            DebugLogger.log(TAG, "initProvider() FAILED: appContext is null")
            return
        }
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
     * Hook Track.setTrackLyric(TrackLyric) 在内存中拦截歌词数据。
     *
     * 逆向分析结论：
     * - track_v2 API 响应使用 ServerPriorityStrategy，不写入磁盘缓存
     * - 歌词数据仅存在于内存的 Track.trackLyric 字段中
     * - Track.setTrackLyric(TrackLyric) 是设置歌词的唯一入口
     * - TrackLyric 包含：lyric(LRC/KRC文本)、type(LRC/KRC/TEXT)、trackId、langTranslations
     *
     * 通过 hook 此方法，我们可以在歌词被设置到 Track 对象时拦截并提取歌词内容，
     * 存入内存缓存供后续 MediaSession 切歌时使用。
     */
    private fun hookTrackLyric() {
        runCatching {
            val trackLyricClass = "com.luna.common.arch.db.entity.TrackLyric".toClass()
            val trackClass = "com.luna.common.arch.db.entity.Track".toClass()

            trackClass.resolve()
                .firstMethod {
                    name = "setTrackLyric"
                    parameters(trackLyricClass)
                }.hook {
                    after {
                        val trackLyric = args.getOrNull(0) ?: return@after
                        DebugLogger.log(TAG, "[setTrackLyric] intercepted, trackLyric class=${trackLyric.javaClass.name}")

                        val cache = extractLyricFromTrackLyric(trackLyric)
                        if (cache != null) {
                            val trackId = cache.lyric?.let { getTrackIdFromTrackLyric(trackLyric) }
                                ?: cache.track?.name?.let { "unknown" }
                            DebugLogger.log(TAG, "[setTrackLyric] extracted lyric: trackId=$trackId, type=${cache.lyric?.type}, content.length=${cache.lyric?.content?.length}, translations.keys=${cache.lyric?.lang_translations?.keys}")

                            // 以 trackId 为 key 存入缓存
                            val tid = getTrackIdFromTrackLyric(trackLyric)
                            if (!tid.isNullOrBlank()) {
                                lyricCache[tid] = cache
                                DebugLogger.log(TAG, "[setTrackLyric] cached lyric for trackId=$tid, total cached=${lyricCache.size}")
                            }
                            // 同时尝试以当前 mediaId 为 key 存入（解决 trackId 与 mediaId 不一致的情况）
                            curMediaId?.let { mid ->
                                if (lyricCache[mid] == null) {
                                    lyricCache[mid] = cache
                                    DebugLogger.log(TAG, "[setTrackLyric] also cached for current mediaId=$mid")
                                }
                            }
                            // 如果有新的歌词到达，尝试更新当前歌曲
                            updateSongIfNeed()
                        } else {
                            DebugLogger.log(TAG, "[setTrackLyric] extractLyricFromTrackLyric returned null")
                        }
                    }
                }
            DebugLogger.log(TAG, "hookTrackLyric() hooks installed successfully")
        }.onFailure {
            DebugLogger.log(TAG, "hookTrackLyric() FAILED to install hooks", it)
        }
    }

    /**
     * 从 TrackLyric Java 对象通过反射提取歌词数据，构建 NetResponseCache。
     *
     * TrackLyric 字段（通过 getter 访问）：
     * - getLyric() -> String: 歌词文本（LRC/KRC格式）
     * - getType() -> NetLyricType 枚举（LRC/KRC/TEXT），getValue() -> "lrc"/"krc"/"text"
     * - getTrackId() -> String: trackId
     * - getLangTranslations() -> Map<NetLyricsLanguage, TrackLyric>: 翻译歌词
     * - getOriginalLyricLang() -> NetLyricsLanguage: 原始语言
     */
    private fun extractLyricFromTrackLyric(trackLyricObj: Any): NetResponseCache? {
        return runCatching {
            val tlClass = trackLyricObj.javaClass

            // 获取歌词文本
            val lyricContent = tlClass.getMethod("getLyric").invoke(trackLyricObj) as? String
            if (lyricContent.isNullOrBlank()) {
                DebugLogger.log(TAG, "extractLyric: lyric content is null/blank")
                return@runCatching null
            }

            // 获取歌词类型
            val typeObj = tlClass.getMethod("getType").invoke(trackLyricObj)
            val typeStr = typeObj?.let {
                it.javaClass.getMethod("getValue").invoke(it) as? String
            }
            DebugLogger.log(TAG, "extractLyric: type=$typeStr, content.length=${lyricContent.length}, preview=${lyricContent.take(80)?.replace("\n", "\\n")}")

            // 获取翻译
            val langTranslations = tlClass.getMethod("getLangTranslations").invoke(trackLyricObj) as? Map<*, *>
            val translationsMap = mutableMapOf<String, NetResponseCache.Translation>()
            if (langTranslations != null) {
                for ((key, value) in langTranslations) {
                    if (key == null || value == null) continue
                    val langStr = key.javaClass.getMethod("getValue").invoke(key) as? String ?: continue
                    val transLyric = value.javaClass.getMethod("getLyric").invoke(value) as? String
                    val transType = value.javaClass.getMethod("getType").invoke(value)?.let { tObj ->
                        tObj.javaClass.getMethod("getValue").invoke(tObj) as? String
                    }
                    if (!transLyric.isNullOrBlank()) {
                        translationsMap[langStr] = NetResponseCache.Translation(
                            content = transLyric,
                            type = transType
                        )
                        DebugLogger.log(TAG, "extractLyric: translation lang=$langStr, type=$transType, length=${transLyric.length}")
                    }
                }
            }

            NetResponseCache(
                lyric = NetResponseCache.Lyric(
                    type = typeStr,
                    content = lyricContent,
                    lang_translations = translationsMap.ifEmpty { null }
                ),
                track = null
            )
        }.onFailure {
            DebugLogger.log(TAG, "extractLyricFromTrackLyric FAILED", it)
            null
        }.getOrNull()
    }

    /**
     * 从 TrackLyric 对象获取 trackId
     */
    private fun getTrackIdFromTrackLyric(trackLyricObj: Any): String? {
        return runCatching {
            trackLyricObj.javaClass.getMethod("getTrackId").invoke(trackLyricObj) as? String
        }.getOrNull()
    }

    private fun updateSongIfNeed() {
        if (curMediaId.isNullOrBlank()) return
        val lastSong = this.lastSong
        if (lastSong?.lyrics.isNullOrEmpty()) {
            DebugLogger.log(TAG, "updateSongIfNeed: lyrics empty, re-triggering updateSong for mediaId=$curMediaId")
            updateSong()
        }
    }

    fun updateSong() {
        val id = curMediaId ?: run {
            DebugLogger.log(TAG, "updateSong: curMediaId is null, aborting")
            return
        }
        DebugLogger.log(TAG, "updateSong: START, mediaId=$id, lyricCache.size=${lyricCache.size}, cachedIds=${lyricCache.keys}")

        // 从内存缓存中查找歌词
        val cache = lyricCache[id]
        if (cache == null) {
            DebugLogger.log(TAG, "updateSong: no lyric in memory cache for mediaId=$id, falling back to MetadataCache only")
            val metadata = MetadataCache.get(id)
            DebugLogger.log(TAG, "updateSong: MetadataCache.get($id) = title=${metadata?.title}, artist=${metadata?.artist}, duration=${metadata?.duration}")
            setSong(Song(name = metadata?.title, artist = metadata?.artist))
            return
        }

        DebugLogger.log(TAG, "updateSong: lyric found in memory cache! lyric.type=${cache.lyric?.type}, lyric.content.length=${cache.lyric?.content?.length}, lyric.lang_translations.keys=${cache.lyric?.lang_translations?.keys}")

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
        val trackName = metadata?.title?.takeIf { it.isNotBlank() }.orEmpty()
        val trackArtist = metadata?.artist?.takeIf { it.isNotBlank() }.orEmpty()
        val trackDuration = metadata?.duration?.takeIf { it != 0L && it != Long.MAX_VALUE } ?: 0L
        DebugLogger.log(TAG, "buildSong: id=$id, trackName=$trackName (fromMeta=${metadata?.title}), trackArtist=$trackArtist (fromMeta=${metadata?.artist}), trackDuration=$trackDuration")
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
}
