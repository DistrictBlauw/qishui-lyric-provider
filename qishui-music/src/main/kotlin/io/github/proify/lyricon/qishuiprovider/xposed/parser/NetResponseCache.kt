@file:Suppress("PropertyName")

package io.github.proify.lyricon.qishuiprovider.xposed.parser

import kotlinx.serialization.Serializable

/**
 * 对应汽水音乐（Luna）网络缓存中的 GetTrackResponse JSON 结构。
 *
 * 逆向分析结论：
 *  - 该 JSON 由 NetCacheLoader 缓存到磁盘，文件路径
 *    `{cacheDir}/NetCacheLoader/{mUid}/{md5("/luna/track_v2/" + trackId)}`
 *  - 缓存有效期 7 天（604800000 ms）
 *  - lyric 字段（TrackLyric）以 JSON 内嵌，非独立文件
 *
 * 此处仅保留歌词展示所需的字段，忽略其余大量业务字段。
 */
@Serializable
class NetResponseCache(
    val lyric: Lyric? = null,
    val track: Track? = null,
) {

    @Serializable
    class Lyric(
        val type: String? = null,
        val content: String? = null,
        val lang_translations: Map<String, Translation>? = null
    )

    @Serializable
    class Translation(
        val content: String? = null,
        val type: String? = null
    )

    /**
     * 对应 GetTrackResponse.track，仅提取标题/艺人/时长等元数据字段，
     * 用于在 MediaSession 未提供元数据时回退补全。
     */
    @Serializable
    class Track(
        val name: String? = null,
        val duration: Long? = null,
        val artists: List<Artist>? = null,
        val album: Album? = null
    ) {
        /** 将 artists 数组拼接为 "A / B" 形式的艺人文本。 */
        val artistsText: String
            get() = artists
                ?.mapNotNull { it.name?.takeIf(String::isNotBlank) }
                ?.takeIf(List<String>::isNotEmpty)
                ?.joinToString(" / ")
                .orEmpty()
    }

    @Serializable
    class Artist(
        val name: String? = null
    )

    @Serializable
    class Album(
        val name: String? = null
    )
}
