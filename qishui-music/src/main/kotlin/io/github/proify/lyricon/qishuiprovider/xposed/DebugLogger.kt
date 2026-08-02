package io.github.proify.lyricon.qishuiprovider.xposed

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志工具，用于在设备上排查歌词获取失败问题。
 *
 * 写入策略：依次尝试以下目录，找到第一个可写位置即使用：
 *  1. /sdcard/qishui-lyric-debug.log        （最直观，用户可直接用文件管理器查看）
 *  2. {appContext.externalCacheDir}/qishui-lyric-debug.log
 *  3. {appContext.cacheDir}/qishui-lyric-debug.log
 *
 * 调用方只需通过 [log] 写入，路径选择由本类内部完成。
 */
object DebugLogger {

    private const val FILE_NAME = "qishui-lyric-debug.log"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L // 2MB，超过则截断重建

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /** 已确定的日志文件（首次写入时解析）。 */
    @Volatile
    private var resolvedFile: File? = null

    /** 外部注入的 appContext（由 QiShui 在 hook 初始化时设置）。 */
    @Volatile
    var appCacheDir: File? = null
    @Volatile
    var appExternalCacheDir: File? = null

    /**
     * 写入一条日志。
     *
     * logcat 输出与文件写入分离：logcat 始终输出（不依赖文件解析结果），
     * 文件写入作为可选附加功能，失败不影响 logcat。
     *
     * @param tag 模块标签
     * @param msg 日志内容
     * @param t 可选异常
     */
    fun log(tag: String = "QiShui", msg: String, t: Throwable? = null) {
        // 1. 始终输出到 logcat（不依赖文件解析，确保 adb logcat 可见）
        try {
            val logMsg = "[$tag] $msg"
            if (t != null) {
                android.util.Log.i("QishuiLyric", logMsg, t)
            } else {
                android.util.Log.i("QishuiLyric", logMsg)
            }
        } catch (_: Throwable) {
            // logcat 不可用时不应崩溃
        }

        // 2. 尝试写入文件（可选，失败不影响 logcat）
        try {
            val file = resolveLogFile() ?: return
            val timestamp = dateFormat.format(Date())
            val throwablePart = t?.let {
                "\n${it.stackTraceToString()}"
            } ?: ""
            val line = "[$timestamp] [$tag] $msg$throwablePart\n"
            file.appendText(line)
        } catch (_: Throwable) {
            // 文件写入失败不应崩溃
        }
    }

    /** 清空日志文件（方便新一轮测试）。 */
    fun clear() {
        try {
            resolveLogFile()?.writeText("")
        } catch (_: Throwable) {
        }
    }

    /** 导出当前日志文件路径，便于用户查找。 */
    fun currentLogPath(): String? = resolveLogFile()?.absolutePath

    private fun resolveLogFile(): File? {
        resolvedFile?.let { existing ->
            // 如果文件过大则截断
            if (existing.exists() && existing.length() > MAX_LOG_SIZE) {
                existing.writeText("")
            }
            return existing
        }

        val candidates = buildList {
            // 1. /sdcard 根目录（最直观）
            runCatching { File("/sdcard/$FILE_NAME") }.getOrNull()?.let { add(it) }
            // 2. externalCacheDir
            appExternalCacheDir?.let { add(File(it, FILE_NAME)) }
            // 3. cacheDir（兜底，一定可写）
            appCacheDir?.let { add(File(it, FILE_NAME)) }
        }

        for (candidate in candidates) {
            try {
                // 尝试创建/追加写入以验证可写
                candidate.parentFile?.mkdirs()
                if (!candidate.exists()) {
                    candidate.createNewFile()
                }
                // 写一条分隔标记
                candidate.appendText("=== DebugLogger resolved path: ${candidate.absolutePath} ===\n")
                resolvedFile = candidate
                return candidate
            } catch (_: Throwable) {
                // 继续尝试下一个候选路径
            }
        }
        return null
    }
}
