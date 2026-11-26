package de.dgstudios.iptvstream.core.player

import java.util.Locale

/** Text der Puffer-Diagnose-Zeile. Reine Logik, testbar. */
object BufferDiag {
    fun line(
        format: String,
        bufferedMs: Long,
        targetMs: Long?,
        speed: Float,
        supply: Float?,
        play: Float?,
        networkKbps: Int?,
        videoKbps: Int?,
        rebuffers: Int,
        reconnects: Int,
    ): String = buildList {
        add(format)
        add("Puffer ${sec(bufferedMs)}" + (targetMs?.let { " / Ziel ${sec(it)}" } ?: ""))
        add("Tempo ${(speed * 100 + 0.5f).toInt()} %")
        add("Zulauf ${supply?.let(FlowMeter::label) ?: "…"}")
        add("Wiedergabe ${play?.let(FlowMeter::label) ?: "…"}")
        add("Netz ${networkKbps?.let(::kbps) ?: "…"}" + (videoKbps?.let { " (Video ${kbps(it)})" } ?: ""))
        add("leer ${rebuffers}×")
        add("neu ${reconnects}×")
    }.joinToString(" · ")

    private fun sec(ms: Long) = String.format(Locale.GERMANY, "%.1f s", ms.coerceAtLeast(0L) / 1000.0)

    private fun kbps(k: Int) = if (k >= 1000) String.format(Locale.GERMANY, "%.1f Mbit/s", k / 1000f) else "$k kbit/s"
}
