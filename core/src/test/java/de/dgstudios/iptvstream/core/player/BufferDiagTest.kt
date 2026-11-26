package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferDiagTest {
    @Test fun lineShowsAllValues() {
        assertEquals(
            "TS · Puffer 4,2 s / Ziel 10,0 s · Tempo 97 % · Zulauf 0,95× · Wiedergabe 0,97× · Netz 4.8 Mbit/s (Video 5.0 Mbit/s) · leer 2× · neu 1×"
                .replace("4.8", "4,8").replace("5.0 M", "5,0 M"),
            BufferDiag.line("TS", 4_200, 10_000, 0.97f, 0.95f, 0.97f, 4_800, 5_000, 2, 1),
        )
    }

    @Test fun unknownValuesShowDots() {
        assertEquals(
            "HLS · Puffer 0,0 s · Tempo 100 % · Zulauf … · Wiedergabe … · Netz … · leer 0× · neu 0×",
            BufferDiag.line("HLS", 0, null, 1f, null, null, null, null, 0, 0),
        )
    }
}
