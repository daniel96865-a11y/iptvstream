package de.dgstudios.iptvstream.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSignalTest {
    @Test
    fun videoUsesOnlyReportedFields() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.4D401F")
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(25f)
            .setAverageBitrate(4_200_000)
            .build()
        assertEquals("1920×1080 · H.264 · 25 fps · 4,2 Mbit/s", StreamSignal.videoLine(format, 1920, 1080))
    }

    @Test
    fun pictureSizeWinsOverContainerSize() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setWidth(1920)
            .setHeight(1088)
            .build()
        assertEquals("1920×1080 · H.265", StreamSignal.videoLine(format, 1920, 1080))
    }

    @Test
    fun missingVideoFactsStayEmpty() {
        assertNull(StreamSignal.videoLine(null, 0, 0))
        val nameless = Format.Builder().setWidth(1280).setHeight(720).build()
        assertEquals("1280×720", StreamSignal.videoLine(nameless, 0, 0))
    }

    @Test
    fun audioUsesChannelCountAndSampleRate() {
        val ac3 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()
        assertEquals("AC3 · 6 Kanäle · 48 kHz", StreamSignal.audioLine(ac3))
        val aac = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        assertEquals("AAC · Stereo · 44,1 kHz", StreamSignal.audioLine(aac))
        assertNull(StreamSignal.audioLine(null))
        assertNull(StreamSignal.audioLine(Format.Builder().build()))
    }

    @Test
    fun fractionalFrameRateIsNotRoundedAway() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setFrameRate(29.97f)
            .build()
        assertEquals("H.264 · 29,97 fps", StreamSignal.videoLine(format, 0, 0))
    }

}
