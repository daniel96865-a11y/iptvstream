package de.dgstudios.iptvstream.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale
import kotlin.math.abs

/**
 * Bild- und Tonangaben nur aus Feldern, die der Player am laufenden Stream meldet.
 * Fehlende Werte bleiben leer. Es wird nichts aus dem Sendernamen oder aus einer Liste abgeleitet.
 */
object StreamSignal {
    fun videoLine(format: Format?, width: Int, height: Int): String? {
        val size = sizeLabel(width, height)
            ?: sizeLabel(format?.width ?: 0, format?.height ?: 0)
        val codec = videoCodec(format?.sampleMimeType, format?.codecs)
        val fps = fpsLabel(format?.frameRate ?: Format.NO_VALUE.toFloat())
        val rate = bitrateLabel(format?.bitrate ?: Format.NO_VALUE)
        return listOfNotNull(size, codec, fps, rate).joinToString(" · ").ifEmpty { null }
    }

    fun audioLine(format: Format?): String? {
        if (format == null) return null
        val codec = audioCodec(format.sampleMimeType, format.codecs)
        val layout = channelLabel(format.channelCount)
        val rate = sampleRateLabel(format.sampleRate)
        return listOfNotNull(codec, layout, rate).joinToString(" · ").ifEmpty { null }
    }

    private fun sizeLabel(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        return "$width×$height"
    }

    private fun videoCodec(mime: String?, codecs: String?): String? {
        val m = mime?.lowercase(Locale.US).orEmpty()
        val c = codecs?.lowercase(Locale.US).orEmpty()
        return when {
            m == MimeTypes.VIDEO_H265 || m.contains("hevc") || c.startsWith("hev1") || c.startsWith("hvc1") -> "H.265"
            m == MimeTypes.VIDEO_H264 || m.contains("/avc") || c.startsWith("avc1") || c.startsWith("avc3") -> "H.264"
            m == MimeTypes.VIDEO_AV1 || c.startsWith("av01") -> "AV1"
            m == MimeTypes.VIDEO_VP9 || c.startsWith("vp09") -> "VP9"
            m == MimeTypes.VIDEO_VP8 || c.startsWith("vp8") -> "VP8"
            m == MimeTypes.VIDEO_MPEG2 || m.contains("mpeg2") -> "MPEG-2"
            m == MimeTypes.VIDEO_MP4V || c.startsWith("mp4v") -> "MPEG-4"
            m.startsWith("video/") -> m.removePrefix("video/")
            else -> null
        }
    }

    private fun audioCodec(mime: String?, codecs: String?): String? {
        val m = mime?.lowercase(Locale.US).orEmpty()
        val c = codecs?.lowercase(Locale.US).orEmpty()
        return when {
            m.contains("eac3") || m.contains("ec-3") || c.startsWith("ec-3") -> "E-AC3"
            m.contains("ac3") || m.contains("ac-3") || c.startsWith("ac-3") -> "AC3"
            m.contains("mp4a") || m.contains("aac") || c.startsWith("mp4a") -> "AAC"
            m.contains("dts") || c.contains("dts") -> "DTS"
            m.contains("truehd") || c.contains("true-hd") -> "TrueHD"
            m.contains("opus") || c.startsWith("opus") -> "Opus"
            m.contains("flac") -> "FLAC"
            m.contains("vorbis") -> "Vorbis"
            m.contains("mpeg-l2") || m.contains("mp2") -> "MP2"
            m.contains("mpeg-l1") || m.contains("mp1") -> "MP1"
            m == MimeTypes.AUDIO_MPEG -> "MP3"
            m.startsWith("audio/") -> m.removePrefix("audio/")
            else -> null
        }
    }

    private fun channelLabel(channels: Int): String? = when (channels) {
        Format.NO_VALUE, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        else -> "$channels Kanäle"
    }

    private fun sampleRateLabel(rate: Int): String? {
        if (rate <= 0 || rate == Format.NO_VALUE) return null
        val khz = rate / 1000.0
        val text = if (rate % 1000 == 0) {
            rate / 1000
        } else {
            String.format(Locale.GERMAN, "%.1f", khz)
        }
        return "$text kHz"
    }

    private fun fpsLabel(rate: Float): String? {
        if (rate <= 0f || rate == Format.NO_VALUE.toFloat()) return null
        val text = if (abs(rate - rate.toInt()) < 0.05f) {
            rate.toInt().toString()
        } else {
            String.format(Locale.GERMAN, "%.2f", rate).trimEnd('0').trimEnd(',')
        }
        return "$text fps"
    }

    private fun bitrateLabel(bps: Int): String? {
        if (bps <= 0 || bps == Format.NO_VALUE) return null
        return if (bps >= 1_000_000) {
            val text = String.format(Locale.GERMAN, "%.1f", bps / 1_000_000.0).removeSuffix(",0")
            "$text Mbit/s"
        } else {
            "${bps / 1000} kbit/s"
        }
    }
}
