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
 * Filme und Serien bleiben beim Verhalten von [delegate].
 */
@UnstableApi
class LiveAwareLoadControl(
    private val delegate: LoadControl,
    private val liveStartMs: Int,
    private val liveAfterRebufferMs: Int,
    private val isLive: () -> Boolean,
    private val stalled: () -> Boolean = { false },
) : LoadControl {

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!isLive()) return delegate.shouldStartPlayback(parameters)
        val needMs = if (parameters.rebuffering || stalled()) liveAfterRebufferMs else liveStartMs
        val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return bufferedUs >= needMs * 1_000L
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters)

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
}
