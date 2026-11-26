package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamErrorPolicyTest {
    @Test fun providerLimitAnswersAreRecognised() {
        for (code in listOf(403, 406, 429, 509)) assertTrue(StreamErrorPolicy.isConnectionLimit(code))
        for (code in listOf(200, 404, 410, 500)) assertFalse(StreamErrorPolicy.isConnectionLimit(code))
    }

    @Test fun limitIsRetriedWithPausesInsteadOfImmediately() {
        assertEquals(2_000, StreamErrorPolicy.limitRetryDelayMs(1))
        assertEquals(3_000, StreamErrorPolicy.limitRetryDelayMs(2))
        assertEquals(4_000, StreamErrorPolicy.limitRetryDelayMs(3))
        assertEquals(4_000, StreamErrorPolicy.limitRetryDelayMs(6))
    }

    @Test fun reconnectAfterALimitWaitsAtLeastThreeSeconds() {
        assertEquals(3_000, ReconnectBackoff.waitMs(1, live = true, limited = true))
        assertEquals(1_000, ReconnectBackoff.waitMs(1, live = true, limited = false))
    }
}
