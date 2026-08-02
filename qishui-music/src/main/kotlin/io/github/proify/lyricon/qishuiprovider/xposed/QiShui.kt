package io.github.proify.lyricon.qishuiprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
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
     * 内存歌词缓存：以 trackId 为权威键；仅在与 curMediaId 匹配时建立 mediaId 别名。
     */
    private val lyricCache = mutableMapOf<String, NetResponseCache>()
    private val lyricCachedAt = mutableMapOf<String, Long>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingRetry: Runnable? = null

    /** 已对某个 mediaId 使用过「最新磁盘缓存」兜底，避免反复串词。 */
    private val newestDiskFallbackUsed = mutableSetOf<String>()

    override fun onHook() {
        // 尽早打日志（仅 Xposed 通道，文件路径尚未 init）
        DebugLogger.i(TAG, "onHook() packageName=$packageName, processName=$processName")

        // 关键：不要等 Application.onCreate。
        // 汽水冷启动可能在 onCreate 前后就 setTrackLyric / setMetadata，等 onCreate 会错过首曲。
        installHooksEarly()

        onAppLifecycle {
            onCreate {
                DebugLogger.i(TAG, "Application.onCreate — init provider & disk loader")
                initProviderAndDisk()
                // Hook 可能已装；再确保一次（幂等）
                installHooksEarly()
                // 若启动时已有当前曲但无词，延迟重试（等磁盘缓存落盘）
                scheduleLyricRetry("onCreate")
            }
        }
    }

    private val hooksInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun installHooksEarly() {
        if (!hooksInstalled.compareAndSet(false, true)) {
            DebugLogger.v(TAG, "installHooksEarly: already installed")
            return
        }
        DebugLogger.i(TAG, "installHooksEarly: hooking MediaSession + Track.setTrackLyric NOW")
        hookMediaSession()
        hookTrackLyric()
    }

    private fun initProviderAndDisk() {
        val context = appContext ?: run {
            DebugLogger.e(TAG, "initProviderAndDisk FAILED: appContext is null")
            return
        }

        val extFiles = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        DebugLogger.init(
            cacheDir = context.cacheDir,
            externalCacheDir = runCatching { context.externalCacheDir }.getOrNull(),
            filesDir = context.filesDir,
            externalFilesDir = extFiles,
            packageName = context.packageName
        )
        DiskLyricLoader.init(
            cacheDir = context.cacheDir,
            externalCacheDir = runCatching { context.externalCacheDir }.getOrNull()
        )

        if (provider == null) {
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
                "provider registered=${provider != null}, info=${provider?.providerInfo}, " +
                    "logPath=${DebugLogger.currentLogPath()}"
            )
        }
    }

    private fun hookMediaSession() {
        runCatching {
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
                                "setMetadata: $previousId -> $id, cacheKeys=${lyricCache.keys}"
                            )
                            updateSong()
                            scheduleLyricRetry("setMetadata")
                        }
                    }
                }
            DebugLogger.i(TAG, "hookMediaSession() OK")
        }.onFailure {
            DebugLogger.e(TAG, "hookMediaSession() FAILED", it)
            hooksInstalled.set(false)
        }
    }

    private fun hookTrackLyric() {
        runCatching {
            val trackLyricClass = "com.luna.common.arch.db.entity.TrackLyric".toClass()
            val trackClass = "com.luna.common.arch.db.entity.Track".toClass()

            // 主路径：setTrackLyric
            trackClass.resolve()
                .firstMethod {
                    name = "setTrackLyric"
                    parameters(trackLyricClass)
                }.hook {
                    after {
                        val trackLyric = args.getOrNull(0) ?: return@after
                        handleTrackLyricObject(trackLyric, source = "setTrackLyric")
                    }
                }

            // 兼容可能存在的其它 setter 名（不存在则静默跳过）
            listOf("updateTrackLyric", "setLyric", "bindLyric").forEach { methodName ->
                runCatching {
                    trackClass.resolve()
                        .firstMethod { name = methodName }
                        .hook {
                            after {
                                val maybe = args.firstOrNull {
                                    it != null && trackLyricClass.isInstance(it)
                                } ?: return@after
                                handleTrackLyricObject(maybe, source = methodName)
                            }
                        }
                    DebugLogger.d(TAG, "also hooked Track.$methodName")
                }
            }

            DebugLogger.i(TAG, "hookTrackLyric() OK")
        }.onFailure {
            DebugLogger.e(TAG, "hookTrackLyric() FAILED", it)
            hooksInstalled.set(false)
        }
    }

    private fun handleTrackLyricObject(trackLyric: Any, source: String) {
        DebugLogger.d(TAG, "[$source] class=${trackLyric.javaClass.name}")
        val cache = extractLyricFromTrackLyric(trackLyric)
        if (cache == null) {
            DebugLogger.w(TAG, "[$source] extract returned null")
            return
        }
        val trackId = getTrackIdFromTrackLyric(trackLyric)
        DebugLogger.i(
            TAG,
            "[$source] trackId=$trackId curMediaId=$curMediaId " +
                "type=${cache.lyric?.type} len=${cache.lyric?.content?.length} " +
                "trans=${cache.lyric?.lang_translations?.keys}"
        )
        onLyricArrived(trackId, cache)
    }

    private fun onLyricArrived(trackId: String?, cache: NetResponseCache) {
        if (trackId.isNullOrBlank()) {
            // 无 trackId：若当前曲还没有歌词，允许临时挂到 curMediaId（仅空缺时）
            val mid = curMediaId
            if (!mid.isNullOrBlank() && lyricCache[mid] == null) {
                DebugLogger.w(TAG, "onLyricArrived: blank trackId, temp bind to curMediaId=$mid")
                lyricCache[mid] = cache
                lyricCachedAt[mid] = System.currentTimeMillis()
                updateSong()
            } else {
                DebugLogger.w(TAG, "onLyricArrived: blank trackId, drop")
            }
            return
        }

        lyricCache[trackId] = cache
        lyricCachedAt[trackId] = System.currentTimeMillis()
        DebugLogger.d(TAG, "onLyricArrived: cached trackId=$trackId total=${lyricCache.size}")

        val mediaId = curMediaId
        val matchesCurrent = !mediaId.isNullOrBlank() && idsMatch(mediaId, trackId)

        if (matchesCurrent) {
            if (mediaId != trackId) {
                lyricCache[mediaId] = cache
                lyricCachedAt[mediaId] = System.currentTimeMillis()
                DebugLogger.d(TAG, "onLyricArrived: alias mediaId=$mediaId -> trackId=$trackId")
            }
            cancelLyricRetry()
            DebugLogger.i(TAG, "onLyricArrived: matches current, force updateSong()")
            updateSong()
            return
        }

        // 当前尚无 mediaId（歌词先到）：只存 trackId，等 setMetadata
        if (mediaId.isNullOrBlank()) {
            DebugLogger.d(TAG, "onLyricArrived: curMediaId null, wait metadata; trackId=$trackId")
            return
        }

        DebugLogger.d(
            TAG,
            "onLyricArrived: not current trackId=$trackId mediaId=$mediaId"
        )
        updateSongIfNeed()
    }

    private fun idsMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true

        val na = normalizeId(a)
        val nb = normalizeId(b)
        if (na.isNotEmpty() && na == nb) return true

        if (na.length >= 8 && nb.length >= 8) {
            if (na.contains(nb) || nb.contains(na)) return true
        }

        val da = digitsOf(a)
        val db = digitsOf(b)
        if (da.length >= 8 && da == db) return true

        return false
    }

    private fun normalizeId(id: String): String =
        id.trim().lowercase()
            .removePrefix("track:")
            .removePrefix("track_")
            .removePrefix("id:")
            .removePrefix("media:")

    private fun digitsOf(id: String): String = buildString(id.length) {
        id.forEach { ch -> if (ch.isDigit()) append(ch) }
    }

    /**
     * 查找顺序：内存精确 → 内存模糊 → 磁盘精确/扫描 →（仅首曲空窗）磁盘最新一份。
     */
    private fun findLyric(mediaId: String, allowNewestDiskFallback: Boolean = false): NetResponseCache? {
        lyricCache[mediaId]?.let {
            DebugLogger.d(TAG, "findLyric: mem exact $mediaId")
            return it
        }
        val matched = lyricCache.entries.firstOrNull { (key, _) -> idsMatch(key, mediaId) }
        if (matched != null) {
            DebugLogger.d(TAG, "findLyric: mem fuzzy $mediaId -> ${matched.key}")
            lyricCache[mediaId] = matched.value
            return matched.value
        }

        // 磁盘
        val disk = DiskLyricLoader.load(mediaId)
        if (disk != null && !disk.lyric?.content.isNullOrBlank()) {
            DebugLogger.i(TAG, "findLyric: DISK hit mediaId=$mediaId type=${disk.lyric?.type}")
            putCacheFor(mediaId, disk)
            // 若磁盘 track 名可用，仅作日志
            disk.track?.name?.let { DebugLogger.d(TAG, "disk track.name=$it") }
            return disk
        }

        if (allowNewestDiskFallback && mediaId !in newestDiskFallbackUsed) {
            val newest = DiskLyricLoader.loadNewestWithLyric()
            if (newest != null) {
                val meta = MetadataCache.get(mediaId)
                val diskTitle = newest.track?.name
                val metaTitle = meta?.title
                val titleOk = diskTitle.isNullOrBlank() || metaTitle.isNullOrBlank() ||
                    diskTitle.contains(metaTitle, ignoreCase = true) ||
                    metaTitle.contains(diskTitle, ignoreCase = true)
                if (titleOk) {
                    newestDiskFallbackUsed += mediaId
                    DebugLogger.w(
                        TAG,
                        "findLyric: NEWEST-disk fallback for $mediaId " +
                            "diskTitle=$diskTitle metaTitle=$metaTitle"
                    )
                    putCacheFor(mediaId, newest)
                    return newest
                }
                DebugLogger.w(
                    TAG,
                    "findLyric: newest disk title mismatch disk=$diskTitle meta=$metaTitle, skip"
                )
            }
        }

        DebugLogger.d(TAG, "findLyric: MISS $mediaId keys=${lyricCache.keys}")
        return null
    }

    private fun putCacheFor(mediaId: String, cache: NetResponseCache) {
        lyricCache[mediaId] = cache
        lyricCachedAt[mediaId] = System.currentTimeMillis()
        // 同时用 digits 形式再存一键，提升后续命中
        val d = digitsOf(mediaId)
        if (d.length >= 8) {
            lyricCache[d] = cache
        }
    }

    private fun extractLyricFromTrackLyric(trackLyricObj: Any): NetResponseCache? {
        return runCatching {
            val tlClass = trackLyricObj.javaClass

            val lyricContent = tlClass.getMethod("getLyric").invoke(trackLyricObj) as? String
            if (lyricContent.isNullOrBlank()) {
                DebugLogger.w(TAG, "extractLyric: content blank")
                return@runCatching null
            }

            val typeObj = tlClass.getMethod("getType").invoke(trackLyricObj)
            val typeStr = typeObj?.let {
                it.javaClass.getMethod("getValue").invoke(it) as? String
            } ?: runCatching { typeObj?.toString() }.getOrNull()

            DebugLogger.d(
                TAG,
                "extractLyric: type=$typeStr len=${lyricContent.length} " +
                    "preview=${lyricContent.take(80).replace("\n", "\\n")}"
            )

            val langTranslations =
                runCatching {
                    tlClass.getMethod("getLangTranslations").invoke(trackLyricObj) as? Map<*, *>
                }.getOrNull()
            val translationsMap = mutableMapOf<String, NetResponseCache.Translation>()
            if (langTranslations != null) {
                for ((key, value) in langTranslations) {
                    if (key == null || value == null) continue
                    val langStr = runCatching {
                        key.javaClass.getMethod("getValue").invoke(key) as? String
                    }.getOrNull() ?: key.toString()
                    val transLyric = runCatching {
                        value.javaClass.getMethod("getLyric").invoke(value) as? String
                    }.getOrNull()
                    val transType = runCatching {
                        val tObj = value.javaClass.getMethod("getType").invoke(value)
                        tObj?.javaClass?.getMethod("getValue")?.invoke(tObj) as? String
                    }.getOrNull()
                    if (!transLyric.isNullOrBlank()) {
                        translationsMap[langStr] = NetResponseCache.Translation(
                            content = transLyric,
                            type = transType
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

    private fun updateSongIfNeed() {
        val id = curMediaId ?: return
        val last = lastSong
        val need =
            last == null ||
                last.id != id ||
                last.lyrics.isNullOrEmpty()
        if (!need) return

        DebugLogger.d(
            TAG,
            "updateSongIfNeed: id=$id lastId=${last?.id} lastLyrics=${last?.lyrics?.size}"
        )
        updateSong()
    }

    fun updateSong() {
        val id = curMediaId ?: run {
            DebugLogger.w(TAG, "updateSong: curMediaId null")
            return
        }
        // provider 可能尚未 init（early hook 阶段）
        if (provider == null) {
            DebugLogger.w(TAG, "updateSong: provider null, will retry after onCreate")
            scheduleLyricRetry("provider-null")
        }

        DebugLogger.d(
            TAG,
            "updateSong: START id=$id memKeys=${lyricCache.keys} provider=${provider != null}"
        )

        val allowNewest = lastSong?.lyrics.isNullOrEmpty()
        val cache = findLyric(id, allowNewestDiskFallback = allowNewest)
        if (cache == null) {
            DebugLogger.w(TAG, "updateSong: no lyric for $id, metadata only + schedule retry")
            val metadata = MetadataCache.get(id)
            setSong(
                Song(
                    id = id,
                    name = metadata?.title,
                    artist = metadata?.artist,
                    duration = metadata?.duration?.takeIf { it != 0L && it != Long.MAX_VALUE } ?: 0L,
                    lyrics = emptyList()
                )
            )
            scheduleLyricRetry("miss")
            return
        }

        val metadata = MetadataCache.get(id)
        // 磁盘缓存可补元数据
        val name = metadata?.title?.takeIf { it.isNotBlank() }
            ?: cache.track?.name.orEmpty()
        val artist = metadata?.artist?.takeIf { it.isNotBlank() }
            ?: cache.track?.artistsText.orEmpty()
        val duration = metadata?.duration?.takeIf { it != 0L && it != Long.MAX_VALUE }
            ?: cache.track?.duration
            ?: 0L

        val lyrics = cache.toRichLyric()
        DebugLogger.i(
            TAG,
            "updateSong: HIT name=$name artist=$artist lyrics=${lyrics.size} type=${cache.lyric?.type}"
        )
        setSong(
            Song(
                id = id,
                name = name,
                artist = artist,
                duration = duration,
                lyrics = lyrics
            )
        )
        if (lyrics.isNotEmpty()) {
            cancelLyricRetry()
        } else {
            scheduleLyricRetry("empty-parse")
        }
    }

    private fun setSong(song: Song) {
        if (song == lastSong) {
            DebugLogger.v(TAG, "setSong: unchanged")
            return
        }
        DebugLogger.i(TAG, "setSong: id=${song.id} name=${song.name} lyrics=${song.lyrics?.size}")
        provider?.player?.setSong(song)
        lastSong = song
    }

    private fun scheduleLyricRetry(reason: String) {
        val id = curMediaId ?: return
        cancelLyricRetry()
        val delays = longArrayOf(300L, 800L, 1500L, 3000L)
        var index = 0
        val runnable = object : Runnable {
            override fun run() {
                val current = curMediaId
                if (current != id) {
                    DebugLogger.d(TAG, "retry aborted, media changed $id -> $current")
                    return
                }
                val last = lastSong
                if (last?.id == id && !last.lyrics.isNullOrEmpty()) {
                    DebugLogger.d(TAG, "retry stop, lyrics ready")
                    return
                }
                DebugLogger.i(TAG, "retry[$index] reason=$reason id=$id")
                // 确保磁盘 loader 已有目录
                if (appContext != null && provider == null) {
                    initProviderAndDisk()
                }
                updateSong()
                index++
                if (index < delays.size && (lastSong?.lyrics.isNullOrEmpty())) {
                    mainHandler.postDelayed(this, delays[index])
                }
            }
        }
        pendingRetry = runnable
        DebugLogger.d(TAG, "scheduleLyricRetry reason=$reason delays=${delays.toList()}")
        mainHandler.postDelayed(runnable, delays[0])
    }

    private fun cancelLyricRetry() {
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingRetry = null
    }
}
