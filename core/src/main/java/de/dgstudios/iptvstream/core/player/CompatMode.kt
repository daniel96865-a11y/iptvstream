package de.dgstudios.iptvstream.core.player

/**
 * Kompatibilitätsmodus für schwache Geräte (ältere Fire-TV-Sticks, 32-Bit, wenig Speicher).
 * Hier steht nur die Entscheidung, ohne Android-Klassen, damit sie testbar bleibt.
 */
object CompatMode {
    const val AUTO = "auto"
    const val ON = "on"
    const val OFF = "off"

    /** Bis zu dieser Heap-Klasse (MB) gilt ein 32-Bit-Gerät als schwach. */
    const val WEAK_MEMORY_CLASS_MB = 192

    const val MAX_WIDTH = 1280
    const val MAX_HEIGHT = 720
    const val LOW_WIDTH = 854
    const val LOW_HEIGHT = 480

    /** Pufferobergrenze im Kompatibilitätsmodus. Schont den kleinen Heap. */
    const val BUFFER_BYTES = 48 * 1024 * 1024

    /** Längster Puffer im Kompatibilitätsmodus (Millisekunden). */
    const val MAX_BUFFER_MS = 25_000

    /** Frist für ein stehendes Bild bei Hardware-Decodern, die ohne Fehler hängen. */
    const val FROZEN_FRAMES_MS = 5_000L

    fun enabled(setting: String?, olderFireTv: Boolean, only32Bit: Boolean, memoryClassMb: Int): Boolean =
        when (setting?.trim()?.lowercase()) {
            ON -> true
            OFF -> false
            else -> olderFireTv || (only32Bit && memoryClassMb in 1..WEAK_MEMORY_CLASS_MB)
        }

    /** Zu viele verworfene Bilder in einem Zeitfenster: Das Gerät schafft die Auflösung nicht. */
    fun overloaded(dropped: Int, rendered: Int): Boolean {
        if (dropped < MIN_DROPPED) return false
        val total = dropped + rendered.coerceAtLeast(0)
        return dropped * 100L >= total * DROP_PERCENT
    }

    private const val MIN_DROPPED = 60
    private const val DROP_PERCENT = 40
}
