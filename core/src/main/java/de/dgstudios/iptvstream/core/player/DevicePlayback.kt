package de.dgstudios.iptvstream.core.player

import android.os.Build

/**
 * Ältere Fire-TV-Sticks spielen den Ton und lassen das Bild nach etwa einer Sekunde stehen.
 * Amazon beschreibt das für Zwischenzeilen-H.264: der Hardware-Decoder hängt, ohne einen Fehler zu melden.
 * Neuere Sticks, deren Modell mit AFTK beginnt, haben das im System behoben.
 * Auf Fire TV keine Betriebsrate und keine asynchrone Warteschlange.
 */
object DevicePlayback {
    /** 4K-Max und Stick 4K ab 2021. Deren Decoder spielt die Sender, die ältere Sticks einfrieren. */
    private val NEWER_STICK = listOf("AFTK")

    fun conservativeVideo(manufacturer: String?, model: String?): Boolean {
        val maker = manufacturer?.trim().orEmpty()
        val name = model?.trim().orEmpty()
        return maker.equals("Amazon", ignoreCase = true) || name.startsWith("AFT", ignoreCase = true)
    }

    fun conservativeVideo(): Boolean = conservativeVideo(Build.MANUFACTURER, Build.MODEL)

    /**
     * Stick vor der 4K-Max-Generation, oder Fire OS 7 und älter.
     * Dort bleibt der Hardware-Decoder bei manchen Sendern stehen.
     */
    fun olderFireTv(manufacturer: String?, model: String?, sdkInt: Int): Boolean {
        if (!conservativeVideo(manufacturer, model)) return false
        val name = model?.trim()?.uppercase().orEmpty()
        if (NEWER_STICK.any { name.startsWith(it) }) return false
        return sdkInt in 1..28
    }

    fun olderFireTv(): Boolean = olderFireTv(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT)

    /** Google- und Android-Softwaredecoder. MediaTek- und andere Hardware-Namen zählen nicht. */
    fun isSoftwareDecoder(name: String?): Boolean {
        val decoder = name?.trim()?.lowercase().orEmpty()
        return decoder.startsWith("omx.google.") || decoder.startsWith("c2.android.")
    }
}

/**
 * Merkt Bilddecoder, die ein Format anzeigen und dann stehen bleiben.
 * Andere Formate behalten ihren Decoder. Sind alle gestrichen, bleibt die ursprüngliche Liste.
 */
class VideoDecoderChoice {
    private val rejected = HashMap<String, MutableSet<String>>()
    private val softwareMimes = HashSet<String>()
    private val restarted = HashSet<String>()

    fun reject(mime: String?, decoder: String?): Boolean {
        val key = key(mime) ?: return false
        val name = decoder?.trim()?.ifEmpty { null } ?: return false
        return rejected.getOrPut(key) { LinkedHashSet() }.add(name)
    }

    /** Dieses Format nur noch mit einem Software-Decoder. Der Hardware-Decoder bleibt stehen. */
    fun useSoftware(mime: String?) {
        key(mime)?.let(softwareMimes::add)
    }

    /** Falls der Software-Decoder selbst stockt, ihn einmal ausschließen und wieder Hardware bevorzugen. */
    fun fallbackFromSoftware(mime: String?, decoder: String?) {
        key(mime)?.let(softwareMimes::remove)
        reject(mime, decoder)
    }

    fun wantsSoftware(mime: String?): Boolean = key(mime)?.let { it in softwareMimes } == true

    fun markRestarted(mime: String?) {
        restarted.add(key(mime) ?: "*")
    }

    fun restarted(mime: String?): Boolean = (key(mime) ?: "*") in restarted

    fun <T> keep(mime: String?, codecs: List<T>, nameOf: (T) -> String): List<T> {
        val blocked = rejected[key(mime)] ?: return codecs
        val kept = codecs.filter { nameOf(it) !in blocked }
        return if (kept.isEmpty()) codecs else kept
    }

    /**
     * Nach einem Hardware-Stillstand nur Software-Decoder.
     * Gibt es keinen, bleibt die Liste aus [keep].
     */
    fun <T> select(
        mime: String?,
        codecs: List<T>,
        nameOf: (T) -> String,
        softwareOnly: (T) -> Boolean,
    ): List<T> {
        val kept = keep(mime, codecs, nameOf)
        if (!wantsSoftware(mime)) return kept
        val software = kept.filter(softwareOnly)
        if (software.isNotEmpty()) return software
        val anySoftware = codecs.filter(softwareOnly)
        return if (anySoftware.isNotEmpty()) anySoftware else kept
    }

    private fun key(mime: String?): String? = mime?.trim()?.lowercase()?.ifEmpty { null }
}
