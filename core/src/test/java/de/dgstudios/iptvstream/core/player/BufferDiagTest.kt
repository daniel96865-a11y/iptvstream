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

    @Test fun showsStallAndReason() {
        assertEquals(
            "TS · Puffer 6,0 s · Tempo 97 % · Zulauf 0,00× · Wiedergabe 0,98× · Netz 0 kbit/s · leer 2× · neu 0× · nahtlos 1× · Stillstand 3,5 s · zuletzt: Stillstand → nahtlos",
            BufferDiag.line("TS", 6_000, null, 0.97f, 0f, 0.98f, 0, null, 2, 0, 1, 3_500, "Stillstand → nahtlos"),
        )
    }

    @Test fun shortSilenceIsHidden() {
        assertEquals(
            "TS · Puffer 6,0 s · Tempo 100 % · Zulauf … · Wiedergabe … · Netz … · leer 0× · neu 0×",
            BufferDiag.line("TS", 6_000, null, 1f, null, null, null, null, 0, 0, 0, 900, null),
        )
    }

    @Test fun showsDecoderNote() {
        assertEquals(
            "TS · Puffer 6,0 s · Tempo 97 % · Zulauf … · Wiedergabe … · Netz … · leer 0× · neu 1× · zuletzt: Stillstand 3 s → Neustart · Decoder: Bild stand (Daten-Stillstand), Decoder bleibt",
            BufferDiag.line("TS", 6_000, null, 0.97f, null, null, null, null, 0, 1, 0, 0, "Stillstand 3 s → Neustart", "Bild stand (Daten-Stillstand), Decoder bleibt"),
        )
    }
}
