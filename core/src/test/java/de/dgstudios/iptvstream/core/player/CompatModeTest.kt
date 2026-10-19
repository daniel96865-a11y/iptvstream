package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatModeTest {
    @Test fun onAndOffOverrideDetection() {
        assertTrue(CompatMode.enabled("on", false, false, 512))
        assertFalse(CompatMode.enabled("off", true, true, 64))
    }

    @Test fun autoFollowsDevice() {
        assertTrue(CompatMode.enabled("auto", true, false, 512))
        assertTrue(CompatMode.enabled(null, false, true, 192))
        assertTrue(CompatMode.enabled("auto", false, false, 512, television = true))
        assertFalse(CompatMode.enabled("auto", false, true, 256))
        assertFalse(CompatMode.enabled("auto", false, false, 128))
        assertFalse(CompatMode.enabled("auto", false, true, 0))
    }

    @Test fun explicitOffStillDisablesTelevisionCompatibility() {
        assertFalse(CompatMode.enabled("off", false, false, 512, television = true))
    }

    @Test fun overloadNeedsManyDroppedFrames() {
        assertFalse(CompatMode.overloaded(10, 0))
        assertFalse(CompatMode.overloaded(100, 400))
        assertTrue(CompatMode.overloaded(100, 100))
        assertTrue(CompatMode.overloaded(60, 0))
        assertEquals(720, CompatMode.MAX_HEIGHT)
    }
}
