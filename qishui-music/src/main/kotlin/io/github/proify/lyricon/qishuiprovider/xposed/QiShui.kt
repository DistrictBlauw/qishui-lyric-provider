package io.github.proify.lyricon.qishuiprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
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
     * 内存歌词缓存：优先以 trackId 为键。
     *
     * 注意：禁止在 trackId 与 curMediaId 未确认匹配时，把歌词盲写到 curMediaId。
     * 冷启动预取其它曲目的 setTrackLyric 曾导致「第一首歌歌词不对」。
     */
    private val lyricCache = mutableMapOf<String, NetResponseCache>()

    /** trackId → 最近一次写入时间，便于日志与调试。 */
    private val lyricCachedAt = mutableMapOf<String, Long>()

    override fun onHook() {
        DebugLogger.i(TAG, "onHook() packageName=$packageName, processName=$processName")

        onAppLifecycle {
            onCreate {
                hook()
            }
        }
    }

    private var hooked = false
    private fun hook() {
        if (hooked) {
            DebugLogger.w(TAG, "hook() already called, skipping")
            return
        }
        hooked = true
        DebugLogger.i(TAG, "hook() first invocation, initializing")

        initProvider()
        hookMediaSession()
        hookTrackLyric()
    }

    private fun initProvider() {
        val context = appContext ?: run {
            DebugLogger.e(TAG, "initProvider() FAILED: appContext is null")
            return
        }

        DebugLogger.init(
            cacheDir = context.cacheDir,
            externalCacheDir = runCatching { context.externalCacheDir }.getOrNull()
        )
        DebugLogger.i(
            TAG,
            "initProvider() appContext=${context.packageName}, " +
                "cacheDir=${context.cacheDir}, externalCacheDir=${context.externalCacheDir}"
        )

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
        DebugLogger.i(
            TAG,
            "initProvider() done, registered=${provider != null}, " +
                "providerInfo=${provider?.providerInfo}, logPath=${DebugLogger.currentLogPath()}"
        )
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

                        DebugLogger.d(
                            TAG,
                            "setMetadata mediaId=$id, " +
                                "title=${mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE)}, " +
                                "artist=${mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}, " +
                                "duration=${mediaMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION)}"
                        )

                        if (id.isNullOrBlank()) {
                            DebugLogger.w(TAG, "setMetadata: blank mediaId, ignore")
                            return@after
                        }

                        if (curMediaId == id) {
                            // 同曲 metadata 刷新：补写 MetadataCache，并在仍无歌词时重试
                            MetadataCache.save(mediaMetadata)
                            DebugLogger.v(TAG, "setMetadata: mediaId unchanged ($id)")
                            updateSongIfNeed()
                            return@after
                        }

                        val previousId = curMediaId
                        curMediaId = id
                        MetadataCache.save(mediaMetadata)
                        DebugLogger.i(
                            TAG,
                            "setMetadata: mediaId $previousId -> $id, cacheKeys=${lyricCache.keys}, calling updateSong()"
                        )
                        // 切歌后必须按新 id 重新解析，避免沿用上一首错误歌词
                        updateSong()
                    }
                }
            }
        DebugLogger.d(TAG, "hookMediaSession() installed")
    }

    /**
     * Hook Track.setTrackLyric(TrackLyric) 在内存中拦截歌词数据。
     *
     * 逆向分析结论：
     * - track_v2 API 响应使用 ServerPriorityStrategy，不写入磁盘缓存
     * - 歌词数据仅存在于内存的 Track.trackLyric 字段中
     * - Track.setTrackLyric(TrackLyric) 是设置歌词的唯一入口
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
                        DebugLogger.d(
                            TAG,
                            "[setTrackLyric] intercepted, class=${trackLyric.javaClass.name}"
                        )

                        val cache = extractLyricFromTrackLyric(trackLyric)
                        if (cache == null) {
                            DebugLogger.w(TAG, "[setTrackLyric] extractLyricFromTrackLyric returned null")
                            return@after
                        }

                        val trackId = getTrackIdFromTrackLyric(trackLyric)
                        DebugLogger.i(
                            TAG,
                            "[setTrackLyric] trackId=$trackId, curMediaId=$curMediaId, " +
                                "type=${cache.lyric?.type}, " +
                                "content.length=${cache.lyric?.content?.length}, " +
                                "translations=${cache.lyric?.lang_translations?.keys}"
                        )

                        onLyricArrived(trackId, cache)
                    }
                }
            DebugLogger.i(TAG, "hookTrackLyric() installed successfully")
        }.onFailure {
            DebugLogger.e(TAG, "hookTrackLyric() FAILED to install hooks", it)
        }
    }

    /**
     * 歌词到达后的统一处理：
     * 1. 只按 trackId 写入权威缓存（可覆盖）
     * 2. 仅当 trackId 与 curMediaId 匹配时建立 mediaId 别名
     * 3. 匹配当前曲则强制 updateSong；否则仅在当前曲仍无歌词时尝试补推
     *
     * 修复：启动后第一首歌歌词不对
     * - 旧逻辑：lyricCache[curMediaId]==null 时把任意预取歌词盲写到 curMediaId
     * - 且 updateSongIfNeed 在已有（错误）歌词后不再刷新
     */
    private fun onLyricArrived(trackId: String?, cache: NetResponseCache) {
        if (trackId.isNullOrBlank()) {
            DebugLogger.w(TAG, "onLyricArrived: blank trackId, skip cache to avoid mis-bind")
            // 没有 trackId 时绝不绑定到 curMediaId，避免张冠李戴
            return
        }

        lyricCache[trackId] = cache
        lyricCachedAt[trackId] = System.currentTimeMillis()
        DebugLogger.d(
            TAG,
            "onLyricArrived: cached trackId=$trackId, total=${lyricCache.size}"
        )

        val mediaId = curMediaId
        val matchesCurrent = !mediaId.isNullOrBlank() && idsMatch(mediaId, trackId)

        if (matchesCurrent) {
            // 建立 mediaId 别名，便于 updateSong 直接命中
            if (mediaId != trackId) {
                lyricCache[mediaId] = cache
                lyricCachedAt[mediaId] = System.currentTimeMillis()
                DebugLogger.d(
                    TAG,
                    "onLyricArrived: alias mediaId=$mediaId -> trackId=$trackId"
                )
            }
            // 当前曲歌词到达：必须强制刷新（即使之前已错误推送过）
            DebugLogger.i(TAG, "onLyricArrived: matches current, force updateSong()")
            updateSong()
            return
        }

        DebugLogger.d(
            TAG,
            "onLyricArrived: not current (trackId=$trackId, mediaId=$mediaId), try updateSongIfNeed"
        )
        updateSongIfNeed()
    }

    /**
     * 判断 MediaSession mediaId 与 TrackLyric trackId 是否指向同一首歌。
     * 兼容完全相等、互相包含、以及纯数字 id 后缀一致等常见差异。
     */
    private fun idsMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true

        val na = normalizeId(a)
        val nb = normalizeId(b)
        if (na.isNotEmpty() && na == nb) return true

        // 互为子串（避免过短误匹配）
        if (na.length >= 8 && nb.length >= 8) {
            if (na.contains(nb) || nb.contains(na)) return true
        }

        val da = digitsOf(a)
        val db = digitsOf(b)
        if (da.length >= 8 && da == db) return true

        return false
    }

    private fun normalizeId(id: String): String =
        id.trim().lowercase().removePrefix("track:").removePrefix("track_").removePrefix("id:")

    private fun digitsOf(id: String): String = buildString(id.length) {
        id.forEach { ch -> if (ch.isDigit()) append(ch) }
    }

    /**
     * 按 mediaId 查找歌词：精确键 → 模糊匹配其它 trackId 键。
     */
    private fun findLyric(mediaId: String): NetResponseCache? {
        lyricCache[mediaId]?.let {
            DebugLogger.d(TAG, "findLyric: exact hit key=$mediaId")
            return it
        }
        val matched = lyricCache.entries.firstOrNull { (key, _) -> idsMatch(key, mediaId) }
        if (matched != null) {
            DebugLogger.d(TAG, "findLyric: fuzzy hit mediaId=$mediaId -> key=${matched.key}")
            // 回填别名，加速后续查找
            lyricCache[mediaId] = matched.value
            return matched.value
        }
        DebugLogger.d(TAG, "findLyric: miss mediaId=$mediaId, keys=${lyricCache.keys}")
        return null
    }

    /**
     * 从 TrackLyric Java 对象通过反射提取歌词数据，构建 NetResponseCache。
     */
    private fun extractLyricFromTrackLyric(trackLyricObj: Any): NetResponseCache? {
        return runCatching {
            val tlClass = trackLyricObj.javaClass

            val lyricContent = tlClass.getMethod("getLyric").invoke(trackLyricObj) as? String
            if (lyricContent.isNullOrBlank()) {
                DebugLogger.w(TAG, "extractLyric: lyric content is null/blank")
                return@runCatching null
            }

            val typeObj = tlClass.getMethod("getType").invoke(trackLyricObj)
            val typeStr = typeObj?.let {
                it.javaClass.getMethod("getValue").invoke(it) as? String
            }
            DebugLogger.d(
                TAG,
                "extractLyric: type=$typeStr, length=${lyricContent.length}, " +
                    "preview=${lyricContent.take(80).replace("\n", "\\n")}"
            )

            val langTranslations =
                tlClass.getMethod("getLangTranslations").invoke(trackLyricObj) as? Map<*, *>
            val translationsMap = mutableMapOf<String, NetResponseCache.Translation>()
            if (langTranslations != null) {
                for ((key, value) in langTranslations) {
                    if (key == null || value == null) continue
                    val langStr =
                        key.javaClass.getMethod("getValue").invoke(key) as? String ?: continue
                    val transLyric =
                        value.javaClass.getMethod("getLyric").invoke(value) as? String
                    val transType = value.javaClass.getMethod("getType").invoke(value)?.let { tObj ->
                        tObj.javaClass.getMethod("getValue").invoke(tObj) as? String
                    }
                    if (!transLyric.isNullOrBlank()) {
                        translationsMap[langStr] = NetResponseCache.Translation(
                            content = transLyric,
                            type = transType
                        )
                        DebugLogger.d(
                            TAG,
                            "extractLyric: translation lang=$langStr, type=$transType, length=${transLyric.length}"
                        )
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
            DebugLogger.e(TAG, "extractLyricFromTrackLyric FAILED", it)
            null
        }.getOrNull()
    }

    private fun getTrackIdFromTrackLyric(trackLyricObj: Any): String? {
        return runCatching {
            trackLyricObj.javaClass.getMethod("getTrackId").invoke(trackLyricObj) as? String
        }.onFailure {
            DebugLogger.w(TAG, "getTrackIdFromTrackLyric failed", it)
        }.getOrNull()
    }

    /**
     * 当前曲尚无可用歌词、或 lastSong 已不是当前 mediaId 时，尝试补推。
     */
    private fun updateSongIfNeed() {
        val id = curMediaId ?: return
        val last = lastSong
        val need =
            last == null ||
                last.id != id ||
                last.lyrics.isNullOrEmpty()
        if (!need) {
            DebugLogger.v(TAG, "updateSongIfNeed: skip, lastSong ok for id=$id")
            return
        }
        // 仅当能查到歌词，或 last 完全缺失时才更新，避免用空数据覆盖
        val hasLyric = findLyric(id) != null
        if (!hasLyric && last != null && last.id == id) {
            DebugLogger.v(TAG, "updateSongIfNeed: no lyric yet for id=$id")
            return
        }
        DebugLogger.d(
            TAG,
            "updateSongIfNeed: refresh id=$id, lastId=${last?.id}, " +
                "lastLyrics=${last?.lyrics?.size}, hasLyric=$hasLyric"
        )
        updateSong()
    }

    fun updateSong() {
        val id = curMediaId ?: run {
            DebugLogger.w(TAG, "updateSong: curMediaId is null, aborting")
            return
        }
        DebugLogger.d(
            TAG,
            "updateSong: START mediaId=$id, cacheSize=${lyricCache.size}, keys=${lyricCache.keys}"
        )

        val cache = findLyric(id)
        if (cache == null) {
            DebugLogger.w(TAG, "updateSong: no lyric for mediaId=$id, metadata only")
            val metadata = MetadataCache.get(id)
            DebugLogger.d(
                TAG,
                "updateSong: MetadataCache title=${metadata?.title}, " +
                    "artist=${metadata?.artist}, duration=${metadata?.duration}"
            )
            setSong(
                Song(
                    id = id,
                    name = metadata?.title,
                    artist = metadata?.artist,
                    duration = metadata?.duration?.takeIf { it != 0L && it != Long.MAX_VALUE } ?: 0L,
                    lyrics = emptyList()
                )
            )
            return
        }

        DebugLogger.i(
            TAG,
            "updateSong: lyric hit type=${cache.lyric?.type}, " +
                "length=${cache.lyric?.content?.length}, " +
                "translations=${cache.lyric?.lang_translations?.keys}"
        )

        val metadata = MetadataCache.get(id)
        val song = cache.buildSong(id, metadata)
        DebugLogger.i(
            TAG,
            "updateSong: buildSong name=${song.name}, artist=${song.artist}, " +
                "duration=${song.duration}, lyrics=${song.lyrics?.size}"
        )
        setSong(song)
    }

    private fun setSong(song: Song) {
        if (song == lastSong) {
            DebugLogger.v(TAG, "setSong: unchanged, skip")
            return
        }
        DebugLogger.i(
            TAG,
            "setSong: push id=${song.id}, name=${song.name}, lyrics=${song.lyrics?.size}"
        )
        provider?.player?.setSong(song)
        lastSong = song
    }

    fun NetResponseCache.buildSong(id: String, metadata: Metadata?): Song {
        val trackName = metadata?.title?.takeIf { it.isNotBlank() }.orEmpty()
        val trackArtist = metadata?.artist?.takeIf { it.isNotBlank() }.orEmpty()
        val trackDuration = metadata?.duration?.takeIf { it != 0L && it != Long.MAX_VALUE } ?: 0L
        DebugLogger.d(
            TAG,
            "buildSong: id=$id, name=$trackName, artist=$trackArtist, duration=$trackDuration"
        )
        val lyrics = toRichLyric()
        DebugLogger.d(TAG, "buildSong: toRichLyric() -> ${lyrics.size} lines")
        return Song(
            id = id,
            name = trackName,
            artist = trackArtist,
            duration = trackDuration,
            lyrics = lyrics
        )
    }
}
