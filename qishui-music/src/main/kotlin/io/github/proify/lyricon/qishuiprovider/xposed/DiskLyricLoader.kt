package io.github.proify.lyricon.qishuiprovider.xposed

import io.github.proify.extensions.json
import io.github.proify.extensions.md5
import io.github.proify.lyricon.qishuiprovider.xposed.parser.NetResponseCache
import java.io.File

/**
 * 从汽水 [NetCacheLoader] 磁盘缓存读取 GetTrackResponse（含 lyric）。
 *
 * 路径约定（逆向）：
 * `{cacheDir|externalCacheDir}/NetCacheLoader/{userId}/{md5("/luna/track_v2/" + trackId)}`
 *
 * 用途：冷启动首曲若错过内存 Hook，用磁盘缓存兜底。
 */
object DiskLyricLoader {

    private const val TAG = "DiskLyric"
    private const val NET_CACHE_DIR = "NetCacheLoader"
    private const val TRACK_PATH_PREFIX = "/luna/track_v2/"

    @Volatile
    private var cacheDir: File? = null

    @Volatile
    private var externalCacheDir: File? = null

    fun init(cacheDir: File?, externalCacheDir: File?) {
        this.cacheDir = cacheDir
        this.externalCacheDir = externalCacheDir
        DebugLogger.d(
            TAG,
            "init cacheDir=$cacheDir externalCacheDir=$externalCacheDir " +
                "netCacheExists=${listNetCacheRoots().map { it.absolutePath to it.exists() }}"
        )
    }

    /**
     * 按 trackId / mediaId 查找磁盘歌词。
     * 1. 精确 MD5 文件名
     * 2. 扫描 NetCacheLoader 下最近修改、含 lyric 且 id 匹配的 JSON
     */
    fun load(trackOrMediaId: String): NetResponseCache? {
        if (trackOrMediaId.isBlank()) return null
        val ids = candidateIds(trackOrMediaId)
        DebugLogger.d(TAG, "load id=$trackOrMediaId candidates=$ids")

        // 精确路径
        for (id in ids) {
            val key = (TRACK_PATH_PREFIX + id).md5()
            for (root in listNetCacheRoots()) {
                val hit = findExact(root, key)
                if (hit != null) {
                    DebugLogger.i(TAG, "exact hit id=$id key=$key file=${hit.second}")
                    return hit.first
                }
            }
        }

        // 扫描兜底（首曲/uid 未知时）
        val scanned = scanRecent(ids)
        if (scanned != null) {
            DebugLogger.i(TAG, "scan hit for id=$trackOrMediaId")
            return scanned
        }

        DebugLogger.w(TAG, "miss id=$trackOrMediaId roots=${listNetCacheRoots().map { it.absolutePath }}")
        return null
    }

    private fun candidateIds(raw: String): List<String> {
        val out = linkedSetOf<String>()
        out += raw.trim()
        val norm = raw.trim()
            .removePrefix("track:")
            .removePrefix("track_")
            .removePrefix("id:")
            .removePrefix("media:")
        out += norm
        val digits = raw.filter { it.isDigit() }
        if (digits.length >= 8) out += digits
        return out.filter { it.isNotBlank() }
    }

    private fun listNetCacheRoots(): List<File> {
        val roots = mutableListOf<File>()
        cacheDir?.let { roots += File(it, NET_CACHE_DIR) }
        externalCacheDir?.let { roots += File(it, NET_CACHE_DIR) }
        return roots.filter { it.exists() && it.isDirectory }
    }

    private fun findExact(root: File, md5Name: String): Pair<NetResponseCache, File>? {
        // root/userId/md5
        val children = root.listFiles() ?: return null
        for (userDir in children) {
            if (!userDir.isDirectory) continue
            val file = File(userDir, md5Name)
            if (!file.isFile || file.length() == 0L) continue
            parse(file)?.let { return it to file }
        }
        // 也允许直接在 root 下
        val direct = File(root, md5Name)
        if (direct.isFile) parse(direct)?.let { return it to direct }
        return null
    }

    private fun scanRecent(ids: List<String>, limitFiles: Int = 40): NetResponseCache? {
        val files = mutableListOf<File>()
        for (root in listNetCacheRoots()) {
            collectFiles(root, files, max = 80)
        }
        if (files.isEmpty()) return null

        files.sortByDescending { it.lastModified() }
        val recent = files.take(limitFiles)
        DebugLogger.d(TAG, "scanRecent files=${recent.size} newest=${recent.firstOrNull()?.name}")

        for (file in recent) {
            val cache = parse(file) ?: continue
            if (cache.lyric?.content.isNullOrBlank()) continue
            // 若 JSON 内嵌 track id 字段有限，用文件名 md5 反推困难；只要有 lyric 且仅一首待匹配时可用
            // 优先：内容里包含任一 id 字符串
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (ids.any { id -> id.length >= 6 && text.contains(id) }) {
                DebugLogger.d(TAG, "scan matched id in file=${file.absolutePath}")
                return cache
            }
        }

        // 最后手段：若当前内存完全没有歌词、且只有「刚刚」写入的缓存，取最新一份有 lyric 的
        // 仅当调用方明确允许；这里不自动采用，避免串词。由上层决定。
        return null
    }

    /**
     * 当精确/id 扫描都失败时，返回最近一份带 lyric 的缓存（仅用于冷启动首曲、缓存为空的场景）。
     * 调用方必须自行承担串词风险，或结合标题再校验。
     */
    fun loadNewestWithLyric(): NetResponseCache? {
        val files = mutableListOf<File>()
        for (root in listNetCacheRoots()) {
            collectFiles(root, files, max = 50)
        }
        files.sortByDescending { it.lastModified() }
        for (file in files.take(15)) {
            val cache = parse(file) ?: continue
            if (!cache.lyric?.content.isNullOrBlank()) {
                DebugLogger.i(
                    TAG,
                    "loadNewestWithLyric file=${file.absolutePath} " +
                        "type=${cache.lyric?.type} len=${cache.lyric?.content?.length} " +
                        "track=${cache.track?.name}"
                )
                return cache
            }
        }
        return null
    }

    private fun collectFiles(dir: File, out: MutableList<File>, max: Int) {
        if (out.size >= max) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (out.size >= max) return
            if (f.isDirectory) {
                collectFiles(f, out, max)
            } else if (f.isFile && f.length() > 32) {
                out += f
            }
        }
    }

    private fun parse(file: File): NetResponseCache? {
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) return@runCatching null
            // 部分缓存可能带长度前缀或非纯 JSON，尽量截取第一个 '{'
            val jsonText = text.trim().let { raw ->
                val idx = raw.indexOf('{')
                if (idx > 0) raw.substring(idx) else raw
            }
            json.decodeFromString(NetResponseCache.serializer(), jsonText)
        }.onFailure {
            DebugLogger.d(TAG, "parse failed file=${file.absolutePath}: ${it.message}")
        }.getOrNull()
    }
}
