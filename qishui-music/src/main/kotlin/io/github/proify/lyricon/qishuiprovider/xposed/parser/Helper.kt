package io.github.proify.lyricon.qishuiprovider.xposed.parser

import io.github.proify.extensions.findClosest
import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.extensions.normalize
import io.github.proify.lyricon.qishuiprovider.xposed.DebugLogger
import java.util.Locale

private const val TAG = "Helper"

fun NetResponseCache.toRichLyric(): List<RichLyricLine> {
    DebugLogger.d(
        TAG,
        "toRichLyric: type=${lyric?.type}, length=${lyric?.content?.length}, " +
            "preview=${lyric?.content?.take(100)?.replace("\n", "\\n")}, " +
            "translations=${lyric?.lang_translations?.keys}"
    )

    val lines = parserTypeLyric(lyric?.type, lyric?.content)?.normalize()
    DebugLogger.d(TAG, "toRichLyric: parsed ${lines?.size ?: "null"} lines (normalized)")
    if (lines.isNullOrEmpty()) {
        DebugLogger.w(
            TAG,
            "toRichLyric: empty lines, type=${lyric?.type} (supported: krc, lrc)"
        )
        return emptyList()
    }

    val langKey = lyric?.lang_translations?.keys?.let { getLangKeyForTranslations(it) }
    val translation = lyric?.lang_translations?.get(langKey.orEmpty())
    DebugLogger.d(
        TAG,
        "toRichLyric: langKey=$langKey, translation.type=${translation?.type}, " +
            "translation.length=${translation?.content?.length}"
    )

    val translationLines = parserTypeLyric(translation?.type, translation?.content)?.normalize()
    DebugLogger.d(TAG, "toRichLyric: translationLines=${translationLines?.size ?: "null"}")

    val result = lines.map { line ->
        val matched = translationLines?.findClosest(line.begin, 50)?.text

        RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = line.text,
            words = line.words,
            translation = if (matched == line.text) null else matched
        )
    }
    DebugLogger.i(TAG, "toRichLyric: DONE, ${result.size} RichLyricLine(s)")
    return result
}

private fun parserTypeLyric(type: String?, lyric: String?): List<LyricLine>? {
    if (type.isNullOrBlank() || lyric.isNullOrBlank()) {
        DebugLogger.v(TAG, "parserTypeLyric: blank type=$type, length=${lyric?.length}")
        return null
    }
    return runCatching {
        when (type.lowercase()) {
            "krc" -> {
                val result = KtvLyricParser.parse(lyric)
                DebugLogger.d(TAG, "parserTypeLyric: KRC -> ${result.size} lines")
                result
            }
            "lrc" -> {
                val result = LrcParser.parse(lyric).lines
                DebugLogger.d(TAG, "parserTypeLyric: LRC -> ${result.size} lines")
                result
            }
            else -> {
                DebugLogger.w(TAG, "parserTypeLyric: unknown type '$type'")
                null
            }
        }
    }.onFailure {
        DebugLogger.e(TAG, "parserTypeLyric: parse FAILED type=$type", it)
    }.getOrNull()
}

/**
 * 根据系统语言匹配 lang_translations 中的 key
 */
private fun getLangKeyForTranslations(availableKeys: Set<String>): String? {
    val locale = Locale.getDefault()
    val systemTag = buildString {
        append(locale.language.uppercase())
        if (locale.script.isNotEmpty()) append("-${locale.script.uppercase()}")
        if (locale.country.isNotEmpty()) append("-${locale.country.uppercase()}")
    }

    availableKeys.firstOrNull { it.equals(systemTag, ignoreCase = true) }?.let { return it }

    if (locale.language == "zh") {
        val fallbackHans = "ZH-HANS-${locale.country.uppercase()}"
        availableKeys.firstOrNull { it.equals(fallbackHans, ignoreCase = true) }?.let { return it }

        val fallbackHant = "ZH-HANT-${locale.country.uppercase()}"
        availableKeys.firstOrNull { it.equals(fallbackHant, ignoreCase = true) }?.let { return it }
    }

    return availableKeys.firstOrNull { it.startsWith(locale.language, ignoreCase = true) }
}
