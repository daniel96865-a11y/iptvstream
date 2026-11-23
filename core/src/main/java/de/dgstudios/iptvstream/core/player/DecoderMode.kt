package de.dgstudios.iptvstream.core.player

/**
 * Einstellung „Video- und Audioverarbeitung“: welche Decoder zuerst probiert werden.
 * Nur die Entscheidung, ohne Android-Klassen, damit sie testbar bleibt.
 *
 * - Nur Hardware: nur Gerätedecoder, kein FFmpeg-Ton.
 * - Hardware bevorzugen (Standard): Gerätedecoder zuerst, Software und FFmpeg als Ausweichlösung.
 * - Software bevorzugen: Software-Decoder und FFmpeg-Ton zuerst, Hardware als Ausweichlösung.
 * - Nur Software: nur Software-Decoder (Bild) und FFmpeg (Ton), sofern es dafür einen gibt.
 */
object DecoderMode {
    const val HW_ONLY = "hw_only"
    const val HW = "hw"
    const val SW = "sw"
    const val SW_ONLY = "sw_only"

    /** Entspricht DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF / _ON / _PREFER. */
    const val EXTENSION_OFF = 0
    const val EXTENSION_ON = 1
    const val EXTENSION_PREFER = 2

    fun normalize(setting: String?): String = when (setting?.trim()?.lowercase()) {
        HW_ONLY -> HW_ONLY
        SW -> SW
        SW_ONLY -> SW_ONLY
        else -> HW
    }

    fun extensionMode(mode: String): Int = when (normalize(mode)) {
        HW_ONLY -> EXTENSION_OFF
        SW, SW_ONLY -> EXTENSION_PREFER
        else -> EXTENSION_ON
    }

    /** FFmpeg darf als letzte Stufe einspringen, wenn ein Gerätedecoder für den Ton abstürzt. */
    fun ffmpegAllowed(mode: String): Boolean = normalize(mode) != HW_ONLY

    /** Ton: Gerätedecoder ganz weglassen, wenn FFmpeg das Format kann. */
    fun audioFfmpegOnly(mode: String, ffmpegSupports: Boolean): Boolean =
        normalize(mode) == SW_ONLY && ffmpegSupports

    /**
     * Reihenfolge der Decoder. Die Plattform liefert Hardware meist zuerst; „Hardware bevorzugen“
     * ändert daran nichts. Ist nach dem Filtern nichts übrig, bleibt die volle Liste, damit überhaupt
     * etwas läuft.
     */
    fun <T> order(mode: String, codecs: List<T>, softwareOnly: (T) -> Boolean): List<T> {
        val m = normalize(mode)
        if (m == HW) return codecs
        val (software, hardware) = codecs.partition(softwareOnly)
        val result = when (m) {
            HW_ONLY -> hardware
            SW -> software + hardware
            else -> software
        }
        return result.ifEmpty { codecs }
    }

    fun label(mode: String): String = when (normalize(mode)) {
        HW_ONLY -> "Nur Hardware"
        SW -> "Software bevorzugen"
        SW_ONLY -> "Nur Software"
        else -> "Hardware bevorzugen"
    }
}
