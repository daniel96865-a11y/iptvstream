package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAwareLoadControlTest {
    @Test fun liveKeepsLoadingWhenTheTimeBufferIsFull() {
        assertTrue(
            LiveAwareLoadControl.continueLoading(
                isLive = true,
                delegateWantsMore = false,
                allocatedBytes = 10_000,
                targetBytes = 192 * 1024 * 1024,
            ),
        )
    }

    @Test fun liveStopsOnlyAtTheByteCap() {
        assertFalse(
            LiveAwareLoadControl.continueLoading(
                isLive = true,
                delegateWantsMore = true,
                allocatedBytes = 192 * 1024 * 1024,
                targetBytes = 192 * 1024 * 1024,
            ),
        )
    }

    @Test fun vodFollowsTheDelegate() {
        assertFalse(
            LiveAwareLoadControl.continueLoading(
                isLive = false,
                delegateWantsMore = false,
                allocatedBytes = 0,
                targetBytes = Int.MAX_VALUE,
            ),
        )
    }
}
