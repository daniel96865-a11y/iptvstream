package de.dgstudios.iptvstream.core.player

/**
 * Merkt Ton-Decoder, die bei einem Format abgestürzt sind (z. B. c2.android.aac.decoder mit
 * ERROR_CODE_DECODING_FAILED). Die Auswahl überspringt sie. Im letzten Schritt bekommt das
 * Format keinen MediaCodec-Decoder mehr, dann übernimmt der FFmpeg-Software-Renderer.
 */
class AudioDecoderChoice {
    private val rejected = HashMap<String, MutableSet<String>>()
    private val ffmpegOnly = HashSet<String>()

    fun reject(mime: String?, decoder: String?): Boolean {
        val key = key(mime) ?: return false
        val name = decoder?.trim()?.ifEmpty { null } ?: return false
        return rejected.getOrPut(key) { LinkedHashSet() }.add(name)
    }

    fun useFfmpegOnly(mime: String?) {
        key(mime)?.let(ffmpegOnly::add)
    }

    fun clearFfmpegOnly(mime: String?) {
        key(mime)?.let(ffmpegOnly::remove)
    }

    fun <T> select(mime: String?, codecs: List<T>, nameOf: (T) -> String): List<T> {
        val key = key(mime) ?: return codecs
        if (key in ffmpegOnly) return emptyList()
        val blocked = rejected[key] ?: return codecs
        val kept = codecs.filter { nameOf(it) !in blocked }
        return if (kept.isEmpty()) codecs else kept
    }

    private fun key(mime: String?): String? = mime?.trim()?.lowercase()?.ifEmpty { null }
}
