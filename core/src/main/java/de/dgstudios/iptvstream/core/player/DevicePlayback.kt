package de.dgstudios.iptvstream.core.player

import android.os.Build

/**
 * Ältere Fire TVs spielen den Ton, lassen das Bild aber bei manchen Sendern stehen.
 * Deren Decoder verträgt keine Betriebsrate und keine asynchrone Warteschlange.
 */
object DevicePlayback {
    fun conservativeVideo(manufacturer: String?, model: String?): Boolean {
        val maker = manufacturer?.trim().orEmpty()
        val name = model?.trim().orEmpty()
        return maker.equals("Amazon", ignoreCase = true) || name.startsWith("AFT", ignoreCase = true)
    }

    fun conservativeVideo(): Boolean = conservativeVideo(Build.MANUFACTURER, Build.MODEL)
}

/**
 * Merkt Bilddecoder, die ein Format anzeigen und dann stehen bleiben.
 * Andere Formate behalten ihren Decoder. Sind alle gestrichen, bleibt die ursprüngliche Liste.
 */
class VideoDecoderChoice {
    private val rejected = HashMap<String, MutableSet<String>>()

    fun reject(mime: String?, decoder: String?): Boolean {
        val key = mime?.trim()?.lowercase()?.ifEmpty { null } ?: return false
        val name = decoder?.trim()?.ifEmpty { null } ?: return false
        return rejected.getOrPut(key) { LinkedHashSet() }.add(name)
    }

    fun <T> keep(mime: String?, codecs: List<T>, nameOf: (T) -> String): List<T> {
        val blocked = rejected[mime?.trim()?.lowercase()] ?: return codecs
        val kept = codecs.filter { nameOf(it) !in blocked }
        return if (kept.isEmpty()) codecs else kept
    }
}
