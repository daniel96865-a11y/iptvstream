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
 * Wie [delegate], aber bei Live-Sendern erst mit einem echten Vorlauf starten bzw. nach einem
 * Hänger weiterspielen ([PlaybackBuffer.liveStart]). Filme und Serien bleiben unverändert schnell.
 */
@UnstableApi
class LiveAwareLoadControl(
    private val delegate: LoadControl,
    private val liveStartMs: Int,
    private val liveAfterRebufferMs: Int,
    private val isLive: () -> Boolean,
    /** Live: Höchstpuffer und Byte-Grenze wie im [delegate]. 0 = Hysterese des Delegaten unverändert. */
    private val liveMaxMs: Long = 0L,
    private val targetBytes: Int = Int.MAX_VALUE,
) : LoadControl {

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!isLive()) return delegate.shouldStartPlayback(parameters)
        val needMs = if (parameters.rebuffering) liveAfterRebufferMs else liveStartMs
        val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return bufferedUs >= needMs * 1_000L
    }

    /**
     * Media3 hört bei vollem Puffer auf zu lesen und fängt erst unter dem Mindestpuffer wieder an (z. B. 40 s → 8 s,
     * also über 30 s Pause). Bei einem Live-Strom ruht die Verbindung dann so lange; manche Anbieter kappen oder
     * drosseln eine Verbindung, die nicht gelesen wird. Live darum schon knapp unter dem Höchstpuffer weiterlesen
     * (Pause nur etwa [LIVE_RESUME_GAP_MS]). Die Byte-Grenze bleibt (Speicher).
     */
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val base = delegate.shouldContinueLoading(parameters)
        if (base || liveMaxMs <= 0L || !isLive()) return base
        if (delegate.getAllocator(parameters.playerId).totalBytesAllocated >= targetBytes) return false
        return parameters.bufferedDurationUs < (liveMaxMs - LIVE_RESUME_GAP_MS) * 1_000L
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
        const val LIVE_RESUME_GAP_MS = 2_000L
    }
}
