package de.dgstudios.iptvstream.core.player

/**
 * Erkennt ein stilles Einfrieren: Der Player meldet "spielt", aber die Position rückt nicht vor.
 * Das passiert zum Beispiel, wenn ein Audiogerät keine Daten mehr abnimmt. Der Player wirft dann
 * keinen Fehler, und die Wiederverbindung greift nie.
 */
class StallDetector(private val thresholdMs: Long = 6_000L) {
    private var lastPosition = -1L
    private var since = 0L

    fun reset() {
        lastPosition = -1L
        since = 0L
    }

    /** Wie lange der beobachtete Wert schon unverändert ist. Null, solange nichts beobachtet wird. */
    fun idleMs(nowMs: Long): Long = if (lastPosition < 0L) 0L else (nowMs - since).coerceAtLeast(0L)

    /** True, wenn der Stillstand [thresholdMs] gedauert hat. Danach läuft die Frist neu. */
    fun observe(nowMs: Long, playing: Boolean, positionMs: Long): Boolean {
        if (!playing) {
            reset()
            return false
        }
        if (positionMs != lastPosition) {
            lastPosition = positionMs
            since = nowMs
            return false
        }
        if (nowMs - since >= thresholdMs) {
            since = nowMs
            return true
        }
        return false
    }
}
