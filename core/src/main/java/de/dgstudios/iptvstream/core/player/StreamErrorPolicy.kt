package de.dgstudios.iptvstream.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Wie Media3s Standard (6 Versuche), aber Antworten, mit denen IPTV-Anbieter ein Verbindungslimit melden
 * (z. B. HTTP 406, 429, 509), werden nicht sofort wiederholt: Sofortige Wiederholungen zählen beim
 * Anbieter als weitere Verbindungen und verlängern die Sperre. Stattdessen 2, 3, 4 … s warten.
 */
@UnstableApi
class StreamErrorPolicy(
    private val onLimit: (code: Int) -> Unit = {},
) : DefaultLoadErrorHandlingPolicy(RETRIES) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val code = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code != null && isConnectionLimit(code)) {
            onLimit(code)
            return limitRetryDelayMs(loadErrorInfo.errorCount)
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    companion object {
        const val RETRIES = 6
        private val LIMIT_CODES = setOf(403, 406, 429, 456, 458, 509)

        /** Typische Antworten auf „zu viele Verbindungen“ bzw. „Verbindung noch belegt“. */
        fun isConnectionLimit(code: Int): Boolean = code in LIMIT_CODES

        /** Wartezeit vor dem n-ten Wiederholversuch (n ab 1): 2, 3, 4, 4 … s. */
        fun limitRetryDelayMs(errorCount: Int): Long =
            if (errorCount < 1) C.TIME_UNSET else (1_000L + errorCount * 1_000L).coerceAtMost(4_000L)
    }
}
