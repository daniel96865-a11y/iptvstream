package de.dgstudios.iptvstream.core.player

/** Times out only when buffering stops making meaningful forward progress. */
internal class BufferProgressWatchdog(
    private val timeoutMs: Long = 10_000L,
    private val minProgressMs: Long = MIN_PROGRESS_MS,
) {
    private var progressAtMs: Long? = null
    private var bufferedPositionMs = 0L

    fun reset() {
        progressAtMs = null
    }

    fun observe(nowMs: Long, active: Boolean, positionMs: Long): Boolean {
        if (!active) {
            reset()
            return false
        }
        val lastProgress = progressAtMs
        if (lastProgress == null || positionMs - bufferedPositionMs >= minProgressMs) {
            progressAtMs = nowMs
            bufferedPositionMs = positionMs
            return false
        }
        if (nowMs - lastProgress < timeoutMs) return false
        progressAtMs = nowMs
        return true
    }

    companion object {
        const val MIN_PROGRESS_MS = 250L
    }
}
