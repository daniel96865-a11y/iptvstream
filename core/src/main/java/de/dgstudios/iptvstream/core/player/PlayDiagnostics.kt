package de.dgstudios.iptvstream.core.player

import androidx.media3.common.Player

/**
 * Sammelt, was der Player an Decodern und Störungen meldet, und fasst es für den Bildschirm in
 * eine Zeile. Gedacht für Geräte, an denen ein Fehler nur dort auftritt und nicht im Log landet.
 */
class PlayDiagnostics {
    @Volatile var videoDecoder: String? = null
    @Volatile var audioDecoder: String? = null
    @Volatile var dropped = 0
    @Volatile var underruns = 0
    @Volatile var lastIssue: String? = null

    fun reset() {
        videoDecoder = null
        audioDecoder = null
        dropped = 0
        underruns = 0
        lastIssue = null
    }

    fun issue(text: String) {
        lastIssue = text.take(MAX_ISSUE)
    }

    fun line(
        version: String,
        playbackState: Int,
        bufferedMs: Long,
        renderedFrames: Int?,
        video: String?,
        audio: String?,
    ): String = buildString {
        append("v").append(version.ifBlank { "?" })
        append(" · ").append(stateName(playbackState))
        append(" · Puffer ").append((bufferedMs.coerceAtLeast(0L) / 1000L)).append(" s")
        if (renderedFrames != null) {
            append(" · Bilder ").append(renderedFrames)
            if (dropped > 0) append(" (verworfen ").append(dropped).append(")")
        }
        append(" · Video ").append(video ?: "keins")
        videoDecoder?.let { append(" [").append(it).append("]") }
        append(" · Ton ").append(audio ?: "keiner")
        audioDecoder?.let { append(" [").append(it).append("]") }
        if (underruns > 0) append(" · Ton-Aussetzer ").append(underruns)
        lastIssue?.let { append(" · ").append(it) }
    }

    companion object {
        private const val MAX_ISSUE = 120

        fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "Leerlauf"
            Player.STATE_BUFFERING -> "Puffern"
            Player.STATE_READY -> "Bereit"
            Player.STATE_ENDED -> "Ende"
            else -> "Zustand $state"
        }
    }
}
