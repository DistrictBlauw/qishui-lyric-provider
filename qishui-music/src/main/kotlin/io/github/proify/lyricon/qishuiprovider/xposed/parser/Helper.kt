package io.github.proify.lyricon.qishuiprovider.xposed.parser

import io.github.proify.extensions.findClosest
import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.extensions.normalize
import io.github.proify.lyricon.qishuiprovider.xposed.DebugLogger
import java.util.Locale

fun NetResponseCache.toRichLyric(): List<RichLyricLine> {
    DebugLogger.log("Helper", "toRichLyric: START, lyric.type=${lyric?.type}, lyric.content.length=${lyric?.content?.length}, lyric.content.preview=${lyric?.content?.take(100)?.replace("\n", "\\n")}, lang_translations.keys=${lyric?.lang_translations?.keys}")

    val lines = parserTypeLyric(lyric?.type, lyric?.content)?.normalize()
    DebugLogger.log("Helper", "toRichLyric: parserTypeLyric returned ${lines?.size ?: "null"} lines (after normalize)")
    if (lines.isNullOrEmpty()) {
        DebugLogger.log("Helper", "toRichLyric: lines is null/empty, returning emptyList. lyric.type=${lyric?.type} (supported: krc, lrc)")
        return emptyList()
    }

    val langKey = lyric?.lang_translations?.keys?.let { getLangKeyForTranslations(it) }
    val translation = lyric?.lang_translations?.get(langKey.orEmpty())
    DebugLogger.log("Helper", "toRichLyric: selected langKey=$langKey, translation.type=${translation?.type}, translation.content.length=${translation?.content?.length}")

    val translationLines = parserTypeLyric(translation?.type, translation?.content)?.normalize()
    DebugLogger.log("Helper", "toRichLyric: translationLines=${translationLines?.size ?: "null"} lines")

    val result = lines.map { line ->
        val translation = translationLines?.findClosest(line.begin, 50)?.text

        RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = line.text,
            words = line.words,
            translation = if (translation == line.text) null else translation
        )
    }
    DebugLogger.log("Helper", "toRichLyric: DONE, returning ${result.size} RichLyricLine(s)")
    return result
}

private fun parserTypeLyric(type: String?, lyric: String?): List<LyricLine>? {
    if (type.isNullOrBlank() || lyric.isNullOrBlank()) {
        DebugLogger.log("Helper", "parserTypeLyric: type or lyric is blank, type=$type, lyric.length=${lyric?.length}")
        return null
    }
    return runCatching {
        when (type.lowercase()) {
            "krc" -> {
                val result = KtvLyricParser.parse(lyric)
                DebugLogger.log("Helper", "parserTypeLyric: KRC parsed ${result.size} lines")
                result
            }
            "lrc" -> {
                val result = LrcParser.parse(lyric).lines
                DebugLogger.log("Helper", "parserTypeLyric: LRC parsed ${result.size} lines")
                result
            }
            else -> {
                DebugLogger.log("Helper", "parserTypeLyric: UNKNOWN lyric type '$type', returning null")
                null
            }
        }
    }.onFailure {
        DebugLogger.log("Helper", "parserTypeLyric: parse FAILED for type=$type", it)
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

    // 精确匹配
    availableKeys.firstOrNull { it.equals(systemTag, ignoreCase = true) }?.let { return it }

    // 中文特殊处理：简体 Hans / 繁体 Hant
    if (locale.language == "zh") {
        val fallbackHans = "ZH-HANS-${locale.country.uppercase()}"
        availableKeys.firstOrNull { it.equals(fallbackHans, ignoreCase = true) }?.let { return it }

        val fallbackHant = "ZH-HANT-${locale.country.uppercase()}"
        availableKeys.firstOrNull { it.equals(fallbackHant, ignoreCase = true) }?.let { return it }
    }

    // 模糊匹配语言部分
    return availableKeys.firstOrNull { it.startsWith(locale.language, ignoreCase = true) }
}