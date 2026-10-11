package de.dgstudios.iptvstream.core.player

import android.media.audiofx.LoudnessEnhancer

/**
 * Hebt den Pegel eines leisen Streams an, ohne die Lautstärke des Geräts zu ändern.
 * Hängt an der Audio-Session des Players. Bei Passthrough (Bitstream) bleibt der Ton unverändert,
 * weil der Receiver ihn dann selbst decodiert.
 */
class StreamGain {
    private var enhancer: LoudnessEnhancer? = null
    private var sessionId: Int = 0
    private var boundSession: Int = 0
    private var millibels: Int = 0
    private var gone = false

    fun setSession(id: Int) {
        if (gone || id <= 0 || id == sessionId) return
        sessionId = id
        apply()
    }

    fun setMillibels(value: Int) {
        if (gone) return
        millibels = value.coerceIn(0, AudioBoost.MAX_MILLIBELS)
        apply()
    }

    fun release() {
        gone = true
        enhancer?.release()
        enhancer = null
        boundSession = 0
        sessionId = 0
    }

    private fun apply() {
        if (millibels <= 0 || sessionId <= 0) {
            try {
                enhancer?.enabled = false
            } catch (_: RuntimeException) {
            }
            return
        }
        if (boundSession != sessionId) {
            enhancer?.release()
            enhancer = null
            boundSession = 0
            val created = try {
                LoudnessEnhancer(sessionId)
            } catch (_: RuntimeException) {
                null
            } ?: return
            enhancer = created
            boundSession = sessionId
        }
        val effect = enhancer ?: return
        if (!setGain(effect, millibels) && millibels > 1_000) setGain(effect, 1_000)
    }

    private fun setGain(effect: LoudnessEnhancer, mb: Int): Boolean = try {
        effect.setTargetGain(mb)
        effect.enabled = true
        true
    } catch (_: RuntimeException) {
        false
    }
}
