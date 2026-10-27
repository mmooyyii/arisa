package arisa.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** 把轨道信息翻译成给人看的文字: "简体中文 · TrueHD · 7.1". */
@OptIn(UnstableApi::class)
object TrackLabels {
    fun language(tag: String?): String? {
        if (tag.isNullOrBlank() || tag == C.LANGUAGE_UNDETERMINED) return null
        val locale = Locale.forLanguageTag(tag)
        if (locale.language == "zh") {
            return when {
                locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO") -> "繁体中文"
                locale.script == "Hans" || locale.country in setOf("CN", "SG") -> "简体中文"
                else -> "中文"
            }
        }
        return locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE).ifBlank { tag }
    }

    /** 字幕在解封装时就解析了, 这时真正的格式在 codecs 里. */
    private fun mime(f: Format) = if (f.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) f.codecs else f.sampleMimeType

    fun codec(f: Format): String? = when (val mime = mime(f)) {
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_E_AC3_JOC -> "DD+ Atmos"
        MimeTypes.AUDIO_E_AC3 -> "DD+"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_AC4 -> "AC-4"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_RAW -> "PCM"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_VTT -> "WebVTT"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_DOLBY_VISION -> "HEVC"
        null -> null
        else -> mime.substringAfter('/').substringBefore(';').uppercase()
    }

    fun channels(count: Int): String? = when {
        count == Format.NO_VALUE || count <= 0 -> null
        count == 1 -> "单声道"
        count == 2 -> "立体声"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "${count} 声道"
    }

    /** 杜比视界 / HDR10 / HLG, SDR 返回 null. */
    fun hdr(f: Format): String? = when {
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dvh") == true -> "杜比视界"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    }

    fun resolution(f: Format): String? = when {
        f.width <= 0 || f.height <= 0 -> null
        f.width >= 3200 || f.height >= 2000 -> "4K"
        f.width >= 1800 || f.height >= 1000 -> "1080P"
        f.width >= 1200 || f.height >= 700 -> "720P"
        else -> "${f.height}P"
    }

    /** 轨道名常常已经写了编码或语言 ("TrueHD Atmos 7.1", "国语"), 重复的部分不再加. */
    private fun join(parts: List<String?>): String {
        val out = mutableListOf<String>()
        for (p in parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (out.none { it.contains(p, ignoreCase = true) }) out += p
        }
        return out.joinToString(" · ")
    }

    fun audio(f: Format): String =
        join(listOf(language(f.language), f.label, codec(f), channels(f.channelCount))).ifEmpty { "音轨" }

    fun text(f: Format): String {
        val forced = if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) "强制" else null
        return join(listOf(language(f.language) ?: "未标语言", f.label, codec(f), forced))
    }

    fun video(f: Format): String = join(
        listOf(
            if (f.width > 0) "${f.width}×${f.height}" else null,
            codec(f),
            hdr(f),
            f.frameRate.takeIf { it > 0 }?.let { "%.3f".format(it).trimEnd('0').trimEnd('.') + " fps" },
            f.bitrate.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1e6) },
        )
    )

    /** AudioTrack 实际输出的编码: PCM 是解码后输出, 其它是直通给电视/功放. */
    fun audioOutput(encoding: Int): String = when (encoding) {
        C.ENCODING_AC3 -> "直通 Dolby Digital"
        C.ENCODING_E_AC3 -> "直通 DD+"
        C.ENCODING_E_AC3_JOC -> "直通 DD+ Atmos"
        C.ENCODING_AC4 -> "直通 AC-4"
        C.ENCODING_DOLBY_TRUEHD -> "直通 TrueHD"
        C.ENCODING_DTS -> "直通 DTS"
        C.ENCODING_DTS_HD -> "直通 DTS-HD"
        C.ENCODING_DTS_UHD_P2 -> "直通 DTS:X"
        else -> "PCM (设备解码后输出)"
    }
}
