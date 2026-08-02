package io.github.proify.lyricon.qishuiprovider.xposed

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
 * 设计目标：
 * - 分级输出（VERBOSE / DEBUG / INFO / WARN / ERROR）
 * - 三通道：LSPosed([XposedBridge]) + logcat + 可选文件
 * - 异步写文件，避免阻塞 Hook 线程
 * - 线程安全；文件超过上限自动轮转保留一份备份
 * - 字节系应用可能劫持 [Log]，因此 [XposedBridge] 为主通道
 *
 * 文件候选路径（按优先级）：
 * 1. `/sdcard/qishui-lyric-debug.log`
 * 2. `{externalCacheDir}/qishui-lyric-debug.log`
 * 3. `{cacheDir}/qishui-lyric-debug.log`
 *
 * 用法：
 * ```
 * DebugLogger.init(cacheDir, externalCacheDir)
 * DebugLogger.d("QiShui", "hook installed")
 * DebugLogger.e("QiShui", "failed", throwable)
 * DebugLogger.log("QiShui", "compat info") // 兼容旧 API，等价 INFO
 * ```
 */
object DebugLogger {

    const val GLOBAL_TAG = "QishuiLyric"

    private const val FILE_NAME = "qishui-lyric-debug.log"
    private const val BACKUP_FILE_NAME = "qishui-lyric-debug.log.1"
    private const val MAX_LOG_SIZE = 2L * 1024 * 1024 // 2MB
    private const val QUEUE_CAPACITY = 512
    private const val FLUSH_INTERVAL_MS = 1_000L

    enum class Level(val priority: Int, val label: String) {
        VERBOSE(0, "V"),
        DEBUG(1, "D"),
        INFO(2, "I"),
        WARN(3, "W"),
        ERROR(4, "E");

        fun isAtLeast(min: Level): Boolean = priority >= min.priority
    }

    /** 最低输出级别，低于此级别的日志直接丢弃。默认 DEBUG。 */
    @Volatile
    var minLevel: Level = Level.DEBUG

    @Volatile
    var enableXposedBridge: Boolean = true

    @Volatile
    var enableLogcat: Boolean = true

    @Volatile
    var enableFile: Boolean = true

    private val appCacheDirRef = AtomicReference<File?>(null)
    private val appExternalCacheDirRef = AtomicReference<File?>(null)
    private val resolvedFile = AtomicReference<File?>(null)

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

    // region Public API

    /**
     * 注入应用缓存目录，应在 Provider 初始化时调用一次。
     * 可重复调用；目录变化时会重置文件解析。
     */
    fun init(cacheDir: File?, externalCacheDir: File? = null) {
        appCacheDirRef.set(cacheDir)
        appExternalCacheDirRef.set(externalCacheDir)
        resolvedFile.set(null)
        ensureWriterStarted()
        i("Logger", "init cacheDir=$cacheDir, externalCacheDir=$externalCacheDir")
    }

    /**
     * 兼容旧代码：`DebugLogger.appCacheDir = context.cacheDir`
     */
    @Deprecated("Use init(cacheDir, externalCacheDir)", ReplaceWith("init(appCacheDir, appExternalCacheDir)"))
    var appCacheDir: File?
        get() = appCacheDirRef.get()
        set(value) {
            appCacheDirRef.set(value)
            resolvedFile.set(null)
            ensureWriterStarted()
        }

    /**
     * 兼容旧代码：`DebugLogger.appExternalCacheDir = context.externalCacheDir`
     */
    @Deprecated("Use init(cacheDir, externalCacheDir)", ReplaceWith("init(appCacheDir, appExternalCacheDir)"))
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

    /**
     * 兼容旧 API：等价于 [i]。
     */
    @JvmOverloads
    fun log(tag: String = "QiShui", msg: String, t: Throwable? = null) {
        i(tag, msg, t)
    }

    fun log(level: Level, tag: String, msg: String, t: Throwable? = null) {
        if (!level.isAtLeast(minLevel)) return

        val safeTag = tag.ifBlank { "QiShui" }
        val header = "[$safeTag] $msg"
        val stack = t?.stackTraceToString()

        if (enableXposedBridge) {
            writeXposed(level, header, t)
        }
        if (enableLogcat) {
            writeLogcat(level, header, t)
        }
        if (enableFile) {
            enqueueFile(level, safeTag, msg, stack)
        }
    }

    /** 清空当前日志文件（异步）。 */
    fun clear() {
        ensureWriterStarted()
        queue.offer(CMD_CLEAR)
    }

    /** 请求刷盘（异步，不阻塞）。 */
    fun flush() {
        ensureWriterStarted()
        queue.offer(CMD_FLUSH)
    }

    /** 当前日志文件绝对路径；尚未解析成功时返回 null。 */
    fun currentLogPath(): String? =
        resolvedFile.get()?.absolutePath ?: resolveLogFile()?.absolutePath

    /** 因队列满而丢弃的日志条数。 */
    fun droppedCount(): Long = dropCount.get()

    // endregion

    // region Sinks

    private fun writeXposed(level: Level, header: String, t: Throwable?) {
        try {
            XposedBridge.log("[$GLOBAL_TAG/${level.label}] $header")
            if (t != null) {
                XposedBridge.log(t)
            }
        } catch (_: Throwable) {
            // Xposed 环境不可用时忽略
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
            // 宿主可能 hook/禁用 Log
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

    // endregion

    // region File writer

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
                        try {
                            writer?.flush()
                            writer?.close()
                        } catch (_: Throwable) {
                        }
                        writer = null
                        val file = resolveLogFile()
                        currentFile = file
                        if (file != null) {
                            runCatching { file.writeText("") }
                            writer = openWriter(file, append = true)
                            writeBanner(writer, file, state = "CLEARED")
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
                        val file = resolveLogFile() ?: continue

                        if (currentFile?.absolutePath != file.absolutePath || writer == null) {
                            try {
                                writer?.flush()
                                writer?.close()
                            } catch (_: Throwable) {
                            }
                            currentFile = file
                            writer = openWriter(file, append = true)
                            writeBanner(writer, file, state = "OPEN")
                        }

                        if (file.exists() && file.length() > MAX_LOG_SIZE) {
                            try {
                                writer?.flush()
                                writer?.close()
                            } catch (_: Throwable) {
                            }
                            rotate(file)
                            writer = openWriter(file, append = false)
                            writeBanner(writer, file, state = "ROTATED")
                        }

                        try {
                            writer?.append(item)?.append('\n')
                        } catch (_: Throwable) {
                            try {
                                writer?.close()
                            } catch (_: Throwable) {
                            }
                            writer = null
                            currentFile = null
                            resolvedFile.set(null)
                            continue
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                            try {
                                writer?.flush()
                                lastFlushAt = now
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
        } finally {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Throwable) {
            }
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

    private fun resolveLogFile(): File? {
        resolvedFile.get()?.let { return it }

        val candidates = buildList {
            runCatching { File("/sdcard/$FILE_NAME") }.getOrNull()?.let { add(it) }
            appExternalCacheDirRef.get()?.let { add(File(it, FILE_NAME)) }
            appCacheDirRef.get()?.let { add(File(it, FILE_NAME)) }
        }

        for (candidate in candidates) {
            try {
                candidate.parentFile?.mkdirs()
                if (!candidate.exists()) {
                    candidate.createNewFile()
                }
                FileOutputStream(candidate, true).use { /* probe writable */ }
                if (resolvedFile.compareAndSet(null, candidate)) {
                    return candidate
                }
                return resolvedFile.get()
            } catch (_: Throwable) {
                // try next
            }
        }
        return null
    }

    // endregion
}
