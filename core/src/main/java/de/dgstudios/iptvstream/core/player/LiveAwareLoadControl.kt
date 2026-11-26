package de.dgstudios.iptvstream.core.player

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Wie [delegate], aber bei Live-Sendern: beim Senderwechsel sofort mit kleinem Vorlauf starten,
 * nach einem Hänger erst mit großem Vorlauf weiterspielen ([PlaybackBuffer.liveStart]).
 * [stalled] bleibt für den laufenden Sender gesetzt, auch über eine Neuverbindung hinweg.
 *
 * Laden bei Live ohne Pause: [DefaultLoadControl][androidx.media3.exoplayer.DefaultLoadControl] hört
 * beim Maximum auf und lädt erst unter dem Minimum weiter. Bei einem Live-Strom staut sich dann die
 * Verbindung für viele Sekunden, und manche Anbieter trennen sie. Hier wird bis [liveMaxMs] (und der
 * Byte-Grenze [targetBytes]) durchgehend gelesen ([shouldLoadLive]).
 * Filme und Serien bleiben beim Verhalten von [delegate].
 */
@UnstableApi
class LiveAwareLoadControl(
    private val delegate: LoadControl,
    private val liveStartMs: Int,
    private val liveAfterRebufferMs: Int,
    private val isLive: () -> Boolean,
    private val stalled: () -> Boolean = { false },
    private val liveMaxMs: Int = Int.MAX_VALUE,
    private val liveMinMs: Int = 0,
    private val targetBytes: Int = Int.MAX_VALUE,
) : LoadControl {

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!isLive()) return delegate.shouldStartPlayback(parameters)
        val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return liveStartReached(bufferedUs, parameters.rebuffering || stalled(), liveStartMs, liveAfterRebufferMs)
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (!isLive()) return delegate.shouldContinueLoading(parameters)
        val allocated = delegate.getAllocator(parameters.playerId).totalBytesAllocated.toLong()
        return shouldLoadLive(parameters.bufferedDurationUs, allocated, liveMinMs, liveMaxMs, targetBytes)
    }

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    companion object {
        /**
         * Startbedingung Live: beim Umschalten nur [zapMs] Vorlauf (Bild sofort), nach einem Hänger [afterStallMs].
         * Hängt bewusst nicht an der Speichergrenze: die Grenze darf den Start nie verhindern.
         */
        fun liveStartReached(bufferedPlayoutUs: Long, afterStall: Boolean, zapMs: Int, afterStallMs: Int): Boolean {
            val needMs = if (afterStall) afterStallMs else zapMs
            return bufferedPlayoutUs >= needMs * 1_000L
        }

        /** Unter [minMs] immer laden; darüber bis [maxMs], solange der Speicher unter [targetBytes] bleibt. */
        fun shouldLoadLive(bufferedUs: Long, allocatedBytes: Long, minMs: Int, maxMs: Int, targetBytes: Int): Boolean {
            if (bufferedUs < minMs * 1_000L) return true
            return bufferedUs < maxMs * 1_000L && allocatedBytes < targetBytes
        }
    }
}
