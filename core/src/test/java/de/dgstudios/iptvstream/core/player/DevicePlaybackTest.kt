package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePlaybackTest {
    @Test
    fun fireTvUsesTheConservativeDecoderPath() {
        assertTrue(DevicePlayback.conservativeVideo("Amazon", "AFTMM"))
        assertTrue(DevicePlayback.conservativeVideo("amazon", "AFTT"))
        assertTrue(DevicePlayback.conservativeVideo("Other", "AFTSSS"))
        assertFalse(DevicePlayback.conservativeVideo("Google", "Chromecast"))
        assertFalse(DevicePlayback.conservativeVideo(null, null))
        assertFalse(DevicePlayback.conservativeVideo(" Samsung ", "TV"))
    }

    @Test
    fun aRejectedDecoderIsSkippedOnlyForThatFormat() {
        val choice = VideoDecoderChoice()
        val codecs = listOf("omx.hevc", "c2.hevc", "omx.avc")
        assertEquals(codecs, choice.keep("video/hevc", codecs) { it })
        assertTrue(choice.reject("video/hevc", "omx.hevc"))
        assertFalse(choice.reject("video/hevc", "omx.hevc"))
        assertEquals(listOf("c2.hevc", "omx.avc"), choice.keep("video/hevc", codecs) { it })
        assertEquals(codecs, choice.keep("video/avc", codecs) { it })
        assertTrue(choice.reject(" video/hevc ", "c2.hevc"))
        assertEquals(listOf("omx.avc"), choice.keep("video/hevc", codecs) { it })
        assertTrue(choice.reject("video/hevc", "omx.avc"))
        assertEquals(codecs, choice.keep("video/hevc", codecs) { it })
        assertFalse(choice.reject(null, "omx.hevc"))
        assertFalse(choice.reject("video/hevc", " "))
    }

    @Test
    fun olderSticksAreTheOnesBeforeTheFourKMax() {
        assertTrue(DevicePlayback.olderFireTv("Amazon", "AFTMM", 25))
        assertTrue(DevicePlayback.olderFireTv("Amazon", "AFTSSS", 28))
        assertTrue(DevicePlayback.olderFireTv("Amazon", "AFTSS", 28))
        assertTrue(DevicePlayback.olderFireTv(null, "AFTT", 25))
        assertFalse(DevicePlayback.olderFireTv("Amazon", "AFTKA", 28))
        assertFalse(DevicePlayback.olderFireTv("Amazon", "AFTKRT", 34))
        assertFalse(DevicePlayback.olderFireTv("Amazon", "AFTKM", 30))
        assertFalse(DevicePlayback.olderFireTv("Google", "Chromecast", 25))
        assertFalse(DevicePlayback.olderFireTv(null, null, 25))
    }

    @Test
    fun softwareDecoderNamesAreTheGoogleOnes() {
        assertTrue(DevicePlayback.isSoftwareDecoder("OMX.google.h264.decoder"))
        assertTrue(DevicePlayback.isSoftwareDecoder("c2.android.avc.decoder"))
        assertFalse(DevicePlayback.isSoftwareDecoder("OMX.MTK.VIDEO.DECODER.AVC"))
        assertFalse(DevicePlayback.isSoftwareDecoder("c2.mtk.avc.decoder"))
        assertFalse(DevicePlayback.isSoftwareDecoder(null))
    }

    @Test
    fun aFrozenHardwareDecoderLeavesOnlySoftwareForThatFormat() {
        val choice = VideoDecoderChoice()
        val avc = listOf(
            "OMX.MTK.VIDEO.DECODER.AVC" to false,
            "OMX.google.h264.decoder" to true,
        )
        assertEquals(avc, choice.select("video/avc", avc, { it.first }) { it.second })
        choice.useSoftware("video/avc")
        choice.reject("video/avc", "OMX.MTK.VIDEO.DECODER.AVC")
        assertEquals(listOf(avc[1]), choice.select("video/avc", avc, { it.first }) { it.second })
        assertEquals(avc, choice.select("video/hevc", avc, { it.first }) { it.second })
        assertFalse(choice.restarted("video/avc"))
        choice.markRestarted("video/avc")
        assertTrue(choice.restarted("video/avc"))
        assertFalse(choice.restarted("video/hevc"))
    }

    @Test
    fun legacyFireTvPrefersSoftwareForHdH264ButUhdKeepsHardware() {
        val choice = VideoDecoderChoice()
        val codecs = listOf("OMX.google.h264.decoder" to true, "OMX.MTK.VIDEO.DECODER.AVC" to false)
        assertTrue(DevicePlayback.olderFireTv("Amazon", "AFTMM", 25))
        choice.useSoftware("video/avc")

        assertEquals(
            listOf(codecs[0]),
            choice.select("video/avc", codecs, { it.first }) { it.second },
        )
        val uhdCandidates = UhdPlayback.decodersFor(codecs, uhd = true) { it.second }
        assertEquals(
            listOf(codecs[1]),
            choice.select("video/avc", uhdCandidates, { it.first }) { it.second },
        )
    }

    @Test
    fun softwarePreferenceFallsBackToHardwareWhenNoSoftwareCodecExists() {
        val choice = VideoDecoderChoice()
        val hardware = listOf("OMX.MTK.VIDEO.DECODER.AVC")
        choice.useSoftware("video/avc")

        assertEquals(hardware, choice.select("video/avc", hardware, { it }) { DevicePlayback.isSoftwareDecoder(it) })
    }

    @Test
    fun aStalledSoftwareDecoderCanFallBackToHardwareOnce() {
        val choice = VideoDecoderChoice()
        val codecs = listOf("OMX.google.h264.decoder" to true, "OMX.MTK.VIDEO.DECODER.AVC" to false)
        choice.useSoftware("video/avc")
        choice.fallbackFromSoftware("video/avc", codecs[0].first)

        assertEquals(listOf(codecs[1]), choice.select("video/avc", codecs, { it.first }) { it.second })
    }
}
