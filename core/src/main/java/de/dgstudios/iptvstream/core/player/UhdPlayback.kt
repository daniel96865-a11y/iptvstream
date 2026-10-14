package de.dgstudios.iptvstream.core.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.common.util.UnstableApi
import kotlin.math.max
import kotlin.math.min

/**
 * Entscheidungen für große Bilder (4K), ohne Android-Decoder.
 * Negative Größen sind unbekannt und zählen nicht als 4K.
 */
object UhdPlayback {
    /** Untergrenze für den Decoder-Eingabepuffer eines 4K-Einzelbildes. */
    const val INPUT_FLOOR_BYTES = 12 * 1024 * 1024

    fun isUhd(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val longEdge = max(width, height)
        val shortEdge = min(width, height)
        return longEdge >= 3200 || shortEdge >= 1600
    }

    fun inputFloor(width: Int, height: Int): Int =
        if (isUhd(width, height)) INPUT_FLOOR_BYTES else 0

    /**
     * [current] bleibt, wenn er unbekannt (negativ) oder schon groß genug ist.
     * Eine 0 als Untergrenze ändert nichts.
     */
    fun raisedInputSize(current: Int, floor: Int): Int {
        if (floor <= 0 || current < 0) return current
        return max(current, floor)
    }

    /** Bei 4K nur Hardware, sofern mindestens ein solcher Decoder gemeldet wird. */
    fun <T> decodersFor(codecs: List<T>, uhd: Boolean, softwareOnly: (T) -> Boolean): List<T> {
        if (!uhd) return codecs
        val hardware = codecs.filterNot(softwareOnly)
        return if (hardware.isNotEmpty()) hardware else codecs
    }

    /**
     * Bild in Originalpixeln, aber nie größer als die Fläche.
     * Unbekannte Fläche (0 oder nicht endlich) lässt die Videogröße stehen.
     */
    fun fitInside(videoW: Int, videoH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (videoW <= 0 || videoH <= 0) return 0 to 0
        if (maxW <= 0 || maxH <= 0 || maxW == Int.MAX_VALUE || maxH == Int.MAX_VALUE) {
            return videoW to videoH
        }
        val scale = minOf(1.0, maxW.toDouble() / videoW, maxH.toDouble() / videoH)
        return max(1, (videoW * scale).toInt()) to max(1, (videoH * scale).toInt())
    }
}

/**
 * Hardware-Decoder für 4K. Ein Software-Decoder meldet 4K oft als unterstützt und
 * schafft die Bilder dann nicht. Der Eingabepuffer von Media3 reicht für große
 * HEVC-Einzelbilder nicht immer.
 */
@OptIn(UnstableApi::class)
class UhdVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    maxDroppedFramesToNotify: Int,
    private val decoderChoice: VideoDecoderChoice,
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val all = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        val usable = decoderChoice.keep(format.sampleMimeType, all) { it.name }
        return UhdPlayback.decodersFor(usable, UhdPlayback.isUhd(format.width, format.height)) { it.softwareOnly }
    }

    override fun getCodecOperatingRateV23(targetPlaybackSpeed: Float, format: Format, streamFormats: Array<Format>): Float {
        if (DevicePlayback.conservativeVideo()) return CODEC_OPERATING_RATE_UNSET
        return super.getCodecOperatingRateV23(targetPlaybackSpeed, format, streamFormats)
    }

    override fun getCodecMaxValues(
        codecInfo: MediaCodecInfo,
        format: Format,
        streamFormats: Array<Format>,
    ): CodecMaxValues {
        val base = super.getCodecMaxValues(codecInfo, format, streamFormats)
        var floor = UhdPlayback.inputFloor(format.width, format.height)
        for (candidate in streamFormats) {
            floor = max(floor, UhdPlayback.inputFloor(candidate.width, candidate.height))
        }
        val input = UhdPlayback.raisedInputSize(base.inputSize, floor)
        if (input == base.inputSize) return base
        return CodecMaxValues(base.width, base.height, input)
    }
}
