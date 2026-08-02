package io.github.proify.lyricon.qishuiprovider.xposed

import android.os.Environment
import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 汽水歌词提供器统一日志系统。
 *
 * 三通道：LSPosed([XposedBridge]) + logcat + 文件。
 * 文件优先写到用户可见目录（Download / 外部存储），失败再回退应用缓存。
 */
object DebugLogger {

    const val GLOBAL_TAG = "QishuiLyric"

    private const val FILE_NAME = "qishui-lyric-debug.log"
    private const val BACKUP_FILE_NAME = "qishui-lyric-debug.log.1"
    private const val MAX_LOG_SIZE = 2L * 1024 * 1024
    private const val QUEUE_CAPACITY = 1024
    private const val FLUSH_INTERVAL_MS = 300L

    enum class Level(val priority: Int, val label: String) {
        VERBOSE(0, "V"),
        DEBUG(1, "D"),
        INFO(2, "I"),
        WARN(3, "W"),
        ERROR(4, "E");

        fun isAtLeast(min: Level): Boolean = priority >= min.priority
    }

    @Volatile
    var minLevel: Level = Level.DEBUG

    @Volatile
    var enableXposedBridge: Boolean = true

    @Volatile
    var enableLogcat: Boolean = true

    @Volatile
    var enableFile: Boolean = true

    /** 每条文件日志立即 flush，便于现场排查（略损性能）。 */
    @Volatile
    var forceFlushEachLine: Boolean = true

    private val appCacheDirRef = AtomicReference<File?>(null)
    private val appExternalCacheDirRef = AtomicReference<File?>(null)
    private val appFilesDirRef = AtomicReference<File?>(null)
    private val appExternalFilesDirRef = AtomicReference<File?>(null)
    private val packageNameRef = AtomicReference<String?>(null)
    private val resolvedFile = AtomicReference<File?>(null)
    private val resolveFailedOnce = AtomicBoolean(false)

    private val started = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private val dropCount = AtomicLong(0)

    private val writerExecutor = Executors.newSingleThreadExecutor(
        ThreadFactory { r ->
            Thread(r, "QishuiLyric-LogWriter").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    )

    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun init(
        cacheDir: File?,
        externalCacheDir: File? = null,
        filesDir: File? = null,
        externalFilesDir: File? = null,
        packageName: String? = null
    ) {
        appCacheDirRef.set(cacheDir)
        appExternalCacheDirRef.set(externalCacheDir)
        appFilesDirRef.set(filesDir)
        appExternalFilesDirRef.set(externalFilesDir)
        if (!packageName.isNullOrBlank()) packageNameRef.set(packageName)
        resolvedFile.set(null)
        resolveFailedOnce.set(false)
        ensureWriterStarted()
        // 同步探测一次，把路径立刻打到 Xposed 日志，方便用户在 LSPosed 里看到
        val path = resolveLogFile()?.absolutePath
        i("Logger", "init package=$packageName cacheDir=$cacheDir externalCacheDir=$externalCacheDir logPath=$path")
        if (path == null) {
            e("Logger", "FILE LOG UNAVAILABLE: all candidate paths failed; use LSPosed log tag QishuiLyric")
        }
        flush()
    }

    @Deprecated("Use init(...)", ReplaceWith("init(appCacheDir, appExternalCacheDir)"))
    var appCacheDir: File?
        get() = appCacheDirRef.get()
        set(value) {
            appCacheDirRef.set(value)
            resolvedFile.set(null)
            ensureWriterStarted()
        }

    @Deprecated("Use init(...)", ReplaceWith("init(appCacheDir, appExternalCacheDir)"))
    var appExternalCacheDir: File?
        get() = appExternalCacheDirRef.get()
        set(value) {
            appExternalCacheDirRef.set(value)
            resolvedFile.set(null)
        }

    fun v(tag: String, msg: String, t: Throwable? = null) = log(Level.VERBOSE, tag, msg, t)
    fun d(tag: String, msg: String, t: Throwable? = null) = log(Level.DEBUG, tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null) = log(Level.INFO, tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.ERROR, tag, msg, t)

    @JvmOverloads
    fun log(tag: String = "QiShui", msg: String, t: Throwable? = null) {
        i(tag, msg, t)
    }

    fun log(level: Level, tag: String, msg: String, t: Throwable? = null) {
        if (!level.isAtLeast(minLevel)) return

        val safeTag = tag.ifBlank { "QiShui" }
        val header = "[$safeTag] $msg"
        val stack = t?.stackTraceToString()

        if (enableXposedBridge) writeXposed(level, header, t)
        if (enableLogcat) writeLogcat(level, header, t)
        if (enableFile) enqueueFile(level, safeTag, msg, stack)
    }

    fun clear() {
        ensureWriterStarted()
        queue.offer(CMD_CLEAR)
    }

    fun flush() {
        ensureWriterStarted()
        queue.offer(CMD_FLUSH)
    }

    fun currentLogPath(): String? =
        resolvedFile.get()?.absolutePath ?: resolveLogFile()?.absolutePath

    fun droppedCount(): Long = dropCount.get()

    private fun writeXposed(level: Level, header: String, t: Throwable?) {
        try {
            XposedBridge.log("[$GLOBAL_TAG/${level.label}] $header")
            if (t != null) XposedBridge.log(t)
        } catch (_: Throwable) {
        }
    }

    private fun writeLogcat(level: Level, header: String, t: Throwable?) {
        try {
            when (level) {
                Level.VERBOSE -> Log.v(GLOBAL_TAG, header, t)
                Level.DEBUG -> Log.d(GLOBAL_TAG, header, t)
                Level.INFO -> Log.i(GLOBAL_TAG, header, t)
                Level.WARN -> Log.w(GLOBAL_TAG, header, t)
                Level.ERROR -> Log.e(GLOBAL_TAG, header, t)
            }
        } catch (_: Throwable) {
        }
    }

    private fun enqueueFile(level: Level, tag: String, msg: String, stack: String?) {
        ensureWriterStarted()
        val ts = dateFormat.get()?.format(Date()) ?: System.currentTimeMillis().toString()
        val line = buildString(msg.length + 64) {
            append('[').append(ts).append("] ")
            append(level.label).append('/')
            append(tag).append(": ")
            append(msg)
            if (stack != null) {
                append('\n').append(stack)
            }
        }
        if (!queue.offer(line)) {
            dropCount.incrementAndGet()
        }
    }

    private const val CMD_CLEAR = "__CLEAR__"
    private const val CMD_FLUSH = "__FLUSH__"

    private fun ensureWriterStarted() {
        if (!enableFile) return
        if (!started.compareAndSet(false, true)) return
        writerExecutor.execute { writerLoop() }
    }

    private fun writerLoop() {
        var writer: BufferedWriter? = null
        var currentFile: File? = null
        var lastFlushAt = System.currentTimeMillis()

        try {
            while (!Thread.currentThread().isInterrupted) {
                val item = try {
                    queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }

                if (item == null) {
                    try {
                        writer?.flush()
                        lastFlushAt = System.currentTimeMillis()
                    } catch (_: Throwable) {
                    }
                    continue
                }

                when (item) {
                    CMD_CLEAR -> {
                        closeQuietly(writer)
                        writer = null
                        val file = resolveLogFile()
                        currentFile = file
                        if (file != null) {
                            runCatching { file.writeText("") }
                            writer = openWriter(file, append = true)
                            writeBanner(writer, file, "CLEARED")
                        }
                    }

                    CMD_FLUSH -> {
                        try {
                            writer?.flush()
                            lastFlushAt = System.currentTimeMillis()
                        } catch (_: Throwable) {
                        }
                    }

                    else -> {
                        val file = resolveLogFile()
                        if (file == null) continue

                        if (currentFile?.absolutePath != file.absolutePath || writer == null) {
                            closeQuietly(writer)
                            currentFile = file
                            writer = openWriter(file, append = true)
                            writeBanner(writer, file, "OPEN")
                        }

                        if (file.exists() && file.length() > MAX_LOG_SIZE) {
                            closeQuietly(writer)
                            rotate(file)
                            writer = openWriter(file, append = false)
                            writeBanner(writer, file, "ROTATED")
                        }

                        try {
                            writer?.append(item)?.append('\n')
                            if (forceFlushEachLine) {
                                writer?.flush()
                                lastFlushAt = System.currentTimeMillis()
                            } else {
                                val now = System.currentTimeMillis()
                                if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                                    writer?.flush()
                                    lastFlushAt = now
                                }
                            }
                        } catch (_: Throwable) {
                            closeQuietly(writer)
                            writer = null
                            currentFile = null
                            resolvedFile.set(null)
                        }
                    }
                }
            }
        } finally {
            closeQuietly(writer)
        }
    }

    private fun closeQuietly(writer: BufferedWriter?) {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Throwable) {
        }
    }

    private fun openWriter(file: File, append: Boolean): BufferedWriter {
        file.parentFile?.mkdirs()
        return BufferedWriter(
            OutputStreamWriter(FileOutputStream(file, append), StandardCharsets.UTF_8),
            8 * 1024
        )
    }

    private fun writeBanner(writer: BufferedWriter?, file: File, state: String) {
        if (writer == null) return
        val ts = dateFormat.get()?.format(Date()) ?: ""
        try {
            writer.append(
                "=== $GLOBAL_TAG $state path=${file.absolutePath} at $ts dropped=${dropCount.get()} ==="
            )
            writer.append('\n')
            writer.flush()
        } catch (_: Throwable) {
        }
    }

    private fun rotate(file: File) {
        val backup = File(file.parentFile, BACKUP_FILE_NAME)
        runCatching {
            if (backup.exists()) backup.delete()
            if (file.exists()) file.renameTo(backup)
        }
        runCatching {
            if (!file.exists()) file.createNewFile()
        }
    }

    private fun buildCandidates(): List<File> {
        val pkg = packageNameRef.get() ?: "com.luna.music"
        val list = mutableListOf<File>()

        // 1) 用户最容易看到的位置
        runCatching {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FILE_NAME
            )
        }.getOrNull()?.let { list.add(it) }

        runCatching { File("/sdcard/Download/$FILE_NAME") }.getOrNull()?.let { list.add(it) }
        runCatching { File("/storage/emulated/0/Download/$FILE_NAME") }.getOrNull()?.let { list.add(it) }

        // 2) App 外部目录（文件管理器可见 Android/data/...）
        appExternalFilesDirRef.get()?.let { list.add(File(it, FILE_NAME)) }
        appExternalCacheDirRef.get()?.let { list.add(File(it, FILE_NAME)) }
        runCatching {
            File("/sdcard/Android/data/$pkg/files/$FILE_NAME")
        }.getOrNull()?.let { list.add(it) }
        runCatching {
            File("/sdcard/Android/data/$pkg/cache/$FILE_NAME")
        }.getOrNull()?.let { list.add(it) }
        runCatching {
            File("/storage/emulated/0/Android/data/$pkg/files/$FILE_NAME")
        }.getOrNull()?.let { list.add(it) }

        // 3) 外部存储根（部分机型仍可写）
        runCatching { File("/sdcard/$FILE_NAME") }.getOrNull()?.let { list.add(it) }

        // 4) 内部目录兜底（需 root / adb 查看）
        appFilesDirRef.get()?.let { list.add(File(it, FILE_NAME)) }
        appCacheDirRef.get()?.let { list.add(File(it, FILE_NAME)) }

        return list.distinctBy { it.absolutePath }
    }

    private fun resolveLogFile(): File? {
        resolvedFile.get()?.let { existing ->
            if (existing.exists() || runCatching {
                    existing.parentFile?.mkdirs()
                    existing.createNewFile()
                }.isSuccess
            ) {
                return existing
            }
            resolvedFile.set(null)
        }

        for (candidate in buildCandidates()) {
            try {
                candidate.parentFile?.mkdirs()
                if (!candidate.exists()) {
                    candidate.createNewFile()
                }
                // 探测可写
                FileOutputStream(candidate, true).use { fos ->
                    fos.write(byteArrayOf())
                    fos.fd.sync()
                }
                if (resolvedFile.compareAndSet(null, candidate)) {
                    try {
                        XposedBridge.log("[$GLOBAL_TAG] file log -> ${candidate.absolutePath}")
                    } catch (_: Throwable) {
                    }
                    return candidate
                }
                return resolvedFile.get()
            } catch (_: Throwable) {
                // try next
            }
        }

        if (resolveFailedOnce.compareAndSet(false, true)) {
            try {
                XposedBridge.log("[$GLOBAL_TAG] file log resolve FAILED, candidates tried=${buildCandidates().size}")
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
