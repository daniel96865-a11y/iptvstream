package de.dgstudios.iptvstream.core.player

import java.util.Locale

/**
 * Misst über ein gleitendes Zeitfenster, wie schnell Medienzeit nachgeladen und wie schnell sie abgespielt
 * wird – jeweils als Vielfaches der Echtzeit. Liegt der Zulauf dauerhaft unter der Wiedergabe, läuft der
 * Puffer zwangsläufig leer (Anbieter liefert langsamer als Echtzeit oder der Player spielt zu schnell).
 * Reine Logik ohne Android-Klassen, damit sie testbar bleibt.
 */
class FlowMeter(private val windowMs: Long = WINDOW_MS) {
    private data class Sample(val at: Long, val loaded: Long, val played: Long)

    private val samples = ArrayDeque<Sample>()

    fun reset() = samples.clear()

    /**
     * [loadedMediaMs]: bis wohin geladen ist (Medienzeit), [playedMediaMs]: aktuelle Position.
     * Springt ein Wert zurück (Neuverbindung, Senderwechsel), beginnt die Messung neu.
     */
    fun add(nowMs: Long, loadedMediaMs: Long, playedMediaMs: Long) {
        val last = samples.lastOrNull()
        if (last != null && (loadedMediaMs < last.loaded || playedMediaMs < last.played || nowMs < last.at)) samples.clear()
        samples.addLast(Sample(nowMs, loadedMediaMs, playedMediaMs))
        while (samples.size > 2 && nowMs - samples[1].at >= windowMs) samples.removeFirst()
    }

    /** Zulauf als Vielfaches der Echtzeit (1,0 = genau Echtzeit); null, solange zu wenig gemessen ist. */
    fun supplyRate(): Float? = rate { it.loaded }

    /** Wiedergabe als Vielfaches der Echtzeit; null, solange zu wenig gemessen ist. */
    fun playRate(): Float? = rate { it.played }

    private inline fun rate(value: (Sample) -> Long): Float? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.last()
        val span = last.at - first.at
        if (span < MIN_SPAN_MS) return null
        return (value(last) - value(first)).toFloat() / span
    }

    companion object {
        const val WINDOW_MS = 20_000L
        const val MIN_SPAN_MS = 5_000L

        /** "0,97×" */
        fun label(rate: Float): String = String.format(Locale.GERMANY, "%.2f×", rate)
    }
}
