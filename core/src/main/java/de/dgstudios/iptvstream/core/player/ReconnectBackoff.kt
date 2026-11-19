package de.dgstudios.iptvstream.core.player

/** Wartezeit und Hinweis bei automatischer Neuverbindung. */
object ReconnectBackoff {
    /** Live: kurz und gleichmäßig (1, 2, 3, 3 … s). Filme/Serien: wie bisher 1, 2, 4, 8 s. */
    fun waitMs(attempt: Int, live: Boolean): Long {
        val n = attempt.coerceAtLeast(1)
        return if (live) (n * 1_000L).coerceAtMost(3_000L) else (1_000L shl (n - 1).coerceAtMost(3))
    }

    /** Live: die ersten zwei Versuche still, erst danach ein Hinweis. */
    fun showNotice(attempt: Int, live: Boolean): Boolean = !live || attempt > 2
}
